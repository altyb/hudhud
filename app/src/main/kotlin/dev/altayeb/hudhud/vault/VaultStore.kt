package dev.altayeb.hudhud.vault

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault on disk: a standard KDBX file sealed once more with a key in the phone's security chip. Someone who
 * copies the file off the phone cannot even start guessing the master password. Exports are plain KDBX.
 */
class VaultStore(context: Context) {
    private val dir = context.applicationContext.noBackupFilesDir
    private val file = File(dir, "vault.sealed")
    private val previous = File(dir, "vault.sealed.prev")
    private val bioFile = File(dir, "bio.sealed")
    private val prefs = context.applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun exists() = file.exists()

    fun load(): ByteArray = unseal(file.readBytes())
    fun loadPrevious(): ByteArray? = previous.takeIf { it.exists() }?.let { runCatching { unseal(it.readBytes()) }.getOrNull() }

    /** Temp file, fsync, rename: a kill or a full disk at any point leaves the last good vault in place. */
    fun save(kdbx: ByteArray) {
        val tmp = File(dir, "vault.tmp")
        FileOutputStream(tmp).use { it.write(seal(kdbx)); it.fd.sync() }
        // A damaged current file never replaces the good previous copy.
        if (file.exists() && runCatching { unseal(file.readBytes()) }.isSuccess) {
            val copy = File(dir, "vault.prev.tmp")
            file.copyTo(copy, overwrite = true)
            copy.renameTo(previous)
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("could not replace the vault")
    }

    fun erase() {
        listOf(file, previous, bioFile).forEach { it.delete() }
        keystore().apply { deleteEntry(SEAL); deleteEntry(BIO) }
        prefs.edit().clear().apply()
    }

    var autoLockMinutes: Int
        get() = prefs.getInt("autoLock", 5)
        set(v) = prefs.edit().putInt("autoLock", v).apply()
    var mirrorUri: String?
        get() = prefs.getString("mirror", null)
        set(v) = prefs.edit().putString("mirror", v).apply()

    // Biometric unlock: the master password, sealed by a key that only opens after a fingerprint. New fingerprints void it.

    fun bioEnabled() = bioFile.exists()
    fun bioEncryptCipher(): Cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, bioKey(create = true)) }
    fun bioStore(cipher: Cipher, password: CharArray) {
        val plain = String(password).toByteArray()
        bioFile.writeBytes(cipher.iv + cipher.doFinal(plain))
        plain.fill(0)
    }

    /** Null when the key is gone, for example after a new fingerprint was enrolled; the master password still works. */
    fun bioDecryptCipher(): Cipher? = try {
        val iv = bioFile.readBytes().copyOfRange(0, 12)
        Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, bioKey(create = false), GCMParameterSpec(128, iv)) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        bioDisable(); null
    } catch (e: GeneralSecurityException) {
        bioDisable(); null
    } catch (e: java.io.IOException) {
        bioDisable(); null
    }

    fun bioRead(cipher: Cipher): CharArray {
        val data = bioFile.readBytes()
        val plain = cipher.doFinal(data, 12, data.size - 12)
        return String(plain).toCharArray().also { plain.fill(0) }
    }

    fun bioDisable() {
        bioFile.delete()
        keystore().deleteEntry(BIO)
    }

    private fun seal(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, sealKey()) }
        return c.iv + c.doFinal(data)
    }

    private fun unseal(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, sealKey(), GCMParameterSpec(128, data, 0, 12)) }
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun sealKey(): SecretKey {
        (keystore().getKey(SEAL, null) as SecretKey?)?.let { return it }
        return generate(SEAL) { }
    }

    private fun bioKey(create: Boolean): SecretKey {
        if (create) keystore().deleteEntry(BIO)
        else return keystore().getKey(BIO, null) as SecretKey? ?: throw GeneralSecurityException("no key")
        return generate(BIO) {
            it.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 24) it.setInvalidatedByBiometricEnrollment(true)
        }
    }

    private fun generate(alias: String, extra: (KeyGenParameterSpec.Builder) -> Unit): SecretKey {
        fun make(strong: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            extra(spec)
            if (strong && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec.build()); generateKey() }
        }
        return try {
            make(true)
        } catch (e: StrongBoxUnavailableException) {
            make(false)
        } catch (e: java.security.ProviderException) {
            make(false)
        }
    }

    private companion object {
        const val GCM = "AES/GCM/NoPadding"
        const val SEAL = "hudhud_vault_seal"
        const val BIO = "hudhud_vault_bio"
    }
}

/**
 * The open vault, in this process only. The keyboard runs in another process and never holds it.
 * ponytail: Kotlin Strings cannot be wiped from memory, so entry text can outlive a lock until GC; a real fix means a char-array model everywhere.
 */
object VaultSession {
    // Compose state, so every open vault screen follows changes made by autofill or a save prompt, and a lock.
    var vault: Vault? by androidx.compose.runtime.mutableStateOf(null)
        private set
    private var password: CharArray? = null
    private val main = Handler(Looper.getMainLooper())
    private val lockNow = Runnable { lock() }
    private var receiver = false
    private val writeLock = Any() // one read-change-write at a time, or a later save would drop an earlier one

    val unlocked get() = vault != null

    fun open(context: Context, store: VaultStore, pw: CharArray) {
        val v = try {
            Kdbx.read(store.load(), pw)
        } catch (e: BadPasswordException) {
            throw e
        } catch (e: Exception) {
            // A damaged current file, at either encryption layer, falls back to the copy made by the previous save.
            Kdbx.read(store.loadPrevious() ?: throw e, pw)
        }
        begin(context, store, v, pw)
    }

    fun create(context: Context, store: VaultStore, pw: CharArray) {
        val v = Vault()
        store.save(Kdbx.write(v, pw))
        begin(context, store, v, pw)
    }

    private fun begin(context: Context, store: VaultStore, v: Vault, pw: CharArray) {
        vault = v
        password = pw.copyOf()
        watchScreen(context)
        touch(store)
    }

    fun lock() {
        main.removeCallbacks(lockNow)
        vault = null
        password?.fill('\u0000')
        password = null
    }

    fun touch(store: VaultStore) {
        main.removeCallbacks(lockNow)
        val m = store.autoLockMinutes
        if (m > 0) main.postDelayed(lockNow, m * 60_000L)
    }

    fun storeForBiometric(store: VaultStore, cipher: javax.crypto.Cipher) {
        store.bioStore(cipher, password ?: throw IllegalStateException("locked"))
    }

    /** Password check for changes that matter. */
    fun matches(pw: CharArray) = password?.contentEquals(pw) == true

    /** Saves first, then shows it: a failed write never leaves the screen claiming a change that is not on disk. */
    fun update(context: Context, store: VaultStore, change: (Vault) -> Vault) = synchronized(writeLock) {
        val v = vault ?: throw IllegalStateException("locked")
        val pw = password ?: throw IllegalStateException("locked")
        val next = change(v)
        val bytes = Kdbx.write(next, pw)
        store.save(bytes)
        vault = next
        mirror(context, store, bytes)
    }

    fun changePassword(context: Context, store: VaultStore, new: CharArray) = synchronized(writeLock) {
        val v = vault ?: throw IllegalStateException("locked")
        val bytes = Kdbx.write(v, new)
        store.save(bytes)
        password?.fill('\u0000')
        password = new.copyOf()
        store.bioDisable()
        mirror(context, store, bytes)
    }

    fun export(): ByteArray = Kdbx.write(vault ?: throw IllegalStateException("locked"), password ?: throw IllegalStateException("locked"))

    /**
     * Copy to the file the user picked for sync. The vault is already safe on disk, so a failure here is only a missed copy.
     * ponytail: "wt" truncates first and a picked document cannot be replaced atomically, so a kill mid-write can leave a short sync copy; KDBX block HMACs make it fail on open, never read wrong.
     */
    private fun mirror(context: Context, store: VaultStore, bytes: ByteArray) {
        val uri = store.mirrorUri ?: return
        runCatching {
            context.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")!!.use { it.write(bytes) }
        }
    }

    private fun watchScreen(context: Context) {
        if (receiver) return
        receiver = true
        context.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) = lock()
        }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
    }
}

package dev.altayeb.hudhud

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Learned words and settings in one zip the user saves wherever they like. Not encrypted, so pinned clips
 * and clipboard history are deliberately left out; only the known setting names are ever read back.
 */
object Backup {
    const val RELOAD_KEY = "reloadDictAt"
    private const val DICT = "user.dict"
    private const val PREFS = "settings.txt"
    private const val MAX_BYTES = 32L * 1024 * 1024

    private val BOOLS = setOf(
        "suggestions", "predictNext", "autocorrect", "learn", "clipboard", "autoCaps", "doubleSpacePeriod", "vibrate",
        "sound", "glide", "swipeEdit", "split", "numberRow", "hints", "incognito", "clearClipsOnLock", "secureKeyboard", "vaultChip", "animations",
    )
    private val INTS = setOf("keyHeight", "oneHanded", "accent", "emojiTone")
    private val STRINGS = setOf("base", "lang", "languages")

    class Data(val prefs: Map<String, String>, val dict: File?)

    fun write(out: OutputStream, dict: File, prefs: Map<String, *>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PREFS))
            val text = prefs.filterKeys { it in BOOLS || it in INTS || it in STRINGS }.entries.joinToString("\n") { "${it.key}=${it.value}" }
            zip.write(text.toByteArray())
            zip.closeEntry()
            if (dict.exists()) {
                zip.putNextEntry(ZipEntry(DICT))
                dict.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Throws IOException unless this is a real backup; a damaged dictionary never reaches the caller. */
    fun read(input: InputStream, tmpDict: File): Data {
        var prefs: Map<String, String>? = null
        var hasDict = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    PREFS -> prefs = String(capped(zip)).lines().filter { '=' in it }
                        .associate { it.substringBefore('=') to it.substringAfter('=') }
                    DICT -> { tmpDict.writeBytes(capped(zip)); hasDict = true }
                }
            }
        }
        val p = prefs ?: throw IOException("not a backup")
        if (hasDict && !UserDict.isValid(tmpDict)) throw IOException("damaged dictionary")
        return Data(p, if (hasDict) tmpDict else null)
    }

    private fun capped(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("backup too large")
        }
    }

    fun applyPrefs(values: Map<String, String>, edit: SharedPreferences.Editor) {
        for ((k, v) in values) when (k) {
            in BOOLS -> v.toBooleanStrictOrNull()?.let { edit.putBoolean(k, it) }
            in INTS -> v.toIntOrNull()?.let { edit.putInt(k, it) }
            in STRINGS -> edit.putString(k, v.take(64))
        }
    }
}

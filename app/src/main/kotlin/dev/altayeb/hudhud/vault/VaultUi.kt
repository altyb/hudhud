package dev.altayeb.hudhud.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.altayeb.hudhud.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MIN_MASTER_LENGTH = 12

/** Every vault window: no screenshots, no recording, not shown in the recents preview. */
fun ComponentActivity.secureWindow() {
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
}

/** Copies marked sensitive, so no keyboard keeps them and Android hides the preview. Cleared after 30 s unless something else was copied. */
fun copySecret(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
    var changed = false
    val listener = ClipboardManager.OnPrimaryClipChangedListener { changed = true }
    Handler(Looper.getMainLooper()).post { cm.addPrimaryClipChangedListener(listener) }
    Handler(Looper.getMainLooper()).postDelayed({
        cm.removePrimaryClipChangedListener(listener)
        if (!changed && Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
    }, 30_000L)
}

@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier = Modifier.fillMaxWidth(), error: String? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, singleLine = true,
        label = { Text(stringResource(label)) },
        isError = error != null, supportingText = error?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.vault_hide else R.string.vault_show)) } },
    )
}

/** Shows [content] once the vault is open. With [forceAuth] it asks again even when open, as passkeys need a fresh check. */
@Composable
fun UnlockGate(store: VaultStore, forceAuth: Boolean = false, content: @Composable () -> Unit) {
    var passed by remember { mutableStateOf(!forceAuth && VaultSession.unlocked) }
    if (passed && VaultSession.unlocked) { content(); return }
    if (!store.exists()) { SetupScreen(store) { passed = true }; return }
    UnlockScreen(store) { passed = true }
}

@Composable
private fun SetupScreen(store: VaultStore, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val short = a.isNotEmpty() && a.length < MIN_MASTER_LENGTH
    val differ = b.isNotEmpty() && a != b
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vault_setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.vault_setup_warning))
        SecretField(a, { a = it }, R.string.vault_master, error = if (short) stringResource(R.string.vault_master_short, MIN_MASTER_LENGTH) else null)
        SecretField(b, { b = it }, R.string.vault_master_again, error = if (differ) stringResource(R.string.vault_master_differ) else null)
        if (failed) Text(stringResource(R.string.vault_save_failed), color = MaterialTheme.colorScheme.error)
        if (busy) CircularProgressIndicator() else Button(
            enabled = a.length >= MIN_MASTER_LENGTH && a == b,
            onClick = {
                busy = true; failed = false
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { runCatching { VaultSession.create(context, store, a.toCharArray()) }.isSuccess }
                    busy = false
                    if (ok) done() else failed = true
                }
            },
        ) { Text(stringResource(R.string.vault_create)) }
    }
}

@Composable
private fun UnlockScreen(store: VaultStore, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    fun attempt(chars: CharArray) {
        busy = true; error = null
        scope.launch {
            val e = withContext(Dispatchers.Default) {
                try { VaultSession.open(context, store, chars); null }
                catch (e: BadPasswordException) { R.string.vault_wrong_password }
                catch (e: Exception) { R.string.vault_damaged }
                finally { chars.fill('\u0000') }
            }
            busy = false
            pw = ""
            if (e == null) done() else error = e
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vault_locked), style = MaterialTheme.typography.headlineSmall)
        SecretField(pw, { pw = it }, R.string.vault_master, error = error?.let { stringResource(it) })
        if (busy) CircularProgressIndicator() else {
            Button(enabled = pw.isNotEmpty(), onClick = { attempt(pw.toCharArray()) }) { Text(stringResource(R.string.vault_unlock)) }
            if (Build.VERSION.SDK_INT >= 28 && store.bioEnabled()) TextButton(onClick = {
                val cipher = store.bioDecryptCipher()
                if (cipher == null) error = R.string.vault_bio_gone
                else biometricPrompt(context, cipher) { c -> attempt(store.bioRead(c)) }
            }) { Text(stringResource(R.string.vault_use_fingerprint)) }
        }
    }
}

/** Framework prompt, so the keyboard needs no extra library. Android 9 and newer. */
fun biometricPrompt(context: Context, cipher: javax.crypto.Cipher, onOk: (javax.crypto.Cipher) -> Unit) {
    if (Build.VERSION.SDK_INT < 28) return
    val main = context.mainExecutor
    BiometricPrompt.Builder(context)
        .setTitle(context.getString(R.string.vault_fingerprint_title))
        .setNegativeButton(context.getString(android.R.string.cancel), main) { _, _ -> }
        .build()
        .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), main, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onOk)
            }
        })
}

fun nowEpoch() = System.currentTimeMillis() / 1000

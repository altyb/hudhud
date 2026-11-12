package dev.altayeb.hudhud.vault

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.altayeb.hudhud.BrandDark
import dev.altayeb.hudhud.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unlock and choose a login for another app. Two callers: the Autofill framework, which gets a Dataset back and
 * never shows secrets to the keyboard; and the keyboard's key button, which types one value after an explicit tap.
 */
class FillActivity : ComponentActivity() {
    private val store by lazy { VaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        val pkg = intent.getStringExtra(PKG).orEmpty()
        val domain = intent.getStringExtra(DOMAIN)
        val keyboard = intent.getBooleanExtra(KEYBOARD, false)
        setContent {
            MaterialTheme(colorScheme = BrandDark) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    UnlockGate(store) { Picker(pkg, domain, keyboard) }
                }
            }
        }
    }

    @Composable
    private fun Picker(pkg: String, domain: String?, keyboard: Boolean) {
        val scope = rememberCoroutineScope()
        val entries = VaultSession.vault?.entries.orEmpty()
        val certs = remember(pkg) { AppIdentity.certs(this, pkg) }
        val browser = remember(pkg) { AppIdentity.isBrowser(this, pkg) }
        val matched = remember(entries) {
            when {
                domain != null -> Match.forWeb(entries, domain)
                browser -> emptyList() // the site is unknown here, so every choice asks first
                else -> Match.forApp(entries, pkg, certs)
            }
        }
        var q by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<VaultEntry?>(null) }
        var values by remember { mutableStateOf<VaultEntry?>(null) }
        val shown = remember(q, matched) {
            if (q.isBlank()) matched.ifEmpty { entries.sortedBy { it.title.lowercase() } }
            else entries.filter { e -> listOf(e.title, e.username, e.url).any { it.contains(q.trim(), true) } }
        }
        fun chosen(e: VaultEntry) { if (keyboard) values = e else fillForm(e) }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vault_fill_for, domain ?: pkg), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vault_search)) })
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.uuid }) { e -> EntryRow(e) { if (e in matched) chosen(e) else confirm = e } }
            }
        }
        // Using a login somewhere it was not saved for is how phishing works, so that step is always a separate choice.
        confirm?.let { e ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                text = { Text(stringResource(R.string.vault_fill_unlinked, e.title, Match.host(e.url) ?: "-", domain ?: pkg)) },
                confirmButton = { TextButton(onClick = {
                    confirm = null
                    // Apps get linked to the certificate seen now, so a look-alike later gets nothing.
                    if (domain == null && !browser && certs.isNotEmpty()) scope.launch {
                        val links = certs.map { AppLink(pkg, it) }
                        withContext(Dispatchers.Default) {
                            runCatching { VaultSession.update(this@FillActivity, store) { v ->
                                v.copy(entries = v.entries.map { if (it.uuid == e.uuid) it.copy(apps = (it.apps + links).distinct(), modified = nowEpoch()) else it })
                            } }
                        }
                        chosen(e)
                    } else chosen(e)
                }) { Text(stringResource(R.string.vault_fill_anyway)) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        values?.let { e ->
            AlertDialog(
                onDismissRequest = { values = null },
                title = { Text(e.title) },
                text = { Column {
                    if (e.username.isNotEmpty()) TextButton(onClick = { typeIt(pkg, BRIDGE_TEXT, e.username) }) { Text(stringResource(R.string.vault_type_username)) }
                    if (e.password.isNotEmpty()) TextButton(onClick = { typeIt(pkg, BRIDGE_PASSWORD, e.password) }) { Text(stringResource(R.string.vault_type_password)) }
                    Totp.parse(e.totp)?.let { c -> TextButton(onClick = { typeIt(pkg, BRIDGE_TEXT, Totp.code(c, nowEpoch())) }) { Text(stringResource(R.string.vault_type_code)) } }
                } },
                confirmButton = { TextButton(onClick = { values = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }

    private fun typeIt(pkg: String, kind: Int, text: String) {
        pending = Pending(pkg, kind, text, android.os.SystemClock.elapsedRealtime() + PENDING_MS)
        finish()
    }

    private fun fillForm(e: VaultEntry) {
        val user = intent.getParcelableExtra<AutofillId>(USER_ID)
        val pass = intent.getParcelableExtra<AutofillId>(PASS_ID)
        val b = Dataset.Builder(RemoteViews(packageName, R.layout.vault_autofill_row).apply { setTextViewText(R.id.vault_row_title, e.title) })
        user?.let { b.setValue(it, AutofillValue.forText(e.username)) }
        pass?.let { b.setValue(it, AutofillValue.forText(e.password)) }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, b.build()))
        finish()
    }

    override fun onUserInteraction() { if (VaultSession.unlocked) VaultSession.touch(store) }

    class Pending(val pkg: String, val kind: Int, val text: String, val expires: Long)

    companion object {
        const val PKG = "pkg"
        const val DOMAIN = "domain"
        const val USER_ID = "userId"
        const val PASS_ID = "passId"
        const val KEYBOARD = "keyboard"
        const val BRIDGE_TEXT = 1
        const val BRIDGE_PASSWORD = 2
        private const val PENDING_MS = 10_000L // the keyboard asks as soon as the field has focus again
        @Volatile var pending: Pending? = null

        fun autofillIntent(c: Context, f: ParsedForm) = Intent(c, FillActivity::class.java)
            .putExtra(PKG, f.pkg).putExtra(DOMAIN, f.domain).putExtra(USER_ID, f.username).putExtra(PASS_ID, f.password)

        fun values(b: Dataset.Builder, f: ParsedForm, e: VaultEntry) {
            f.username?.let { b.setValue(it, AutofillValue.forText(e.username)) }
            f.password?.let { b.setValue(it, AutofillValue.forText(e.password)) }
        }
    }
}

/** Hands one chosen value to the keyboard, once, only for the app it was chosen for, and only within 10 seconds. Not exported. */
class VaultBridgeService : Service() {
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            val reply = m.replyTo ?: return
            val p = FillActivity.pending
            val out = Message.obtain()
            val asked = m.data?.getString(FillActivity.PKG)
            val live = p != null && android.os.SystemClock.elapsedRealtime() < p.expires
            if (!live) FillActivity.pending = null
            if (p != null && live && p.pkg == asked) {
                FillActivity.pending = null
                out.what = p.kind
                out.data = Bundle().apply { putString("text", p.text) }
            }
            runCatching { reply.send(out) }
        }
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
}

/** Offered by Android after a login form was submitted with a password that is not saved yet. */
class SaveLoginActivity : ComponentActivity() {
    private val store by lazy { VaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        val pkg = intent.getStringExtra(PKG).orEmpty()
        val domain = intent.getStringExtra(DOMAIN)
        val user = intent.getStringExtra(USER).orEmpty()
        val pass = intent.getStringExtra(PASS).orEmpty()
        setContent {
            MaterialTheme(colorScheme = BrandDark) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    UnlockGate(store) {
                        val scope = rememberCoroutineScope()
                        var failed by remember { mutableStateOf(false) }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.vault_save_login, domain ?: pkg), style = MaterialTheme.typography.titleMedium)
                            Text(user)
                            if (failed) Text(stringResource(R.string.vault_save_failed), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { scope.launch {
                                val ok = withContext(Dispatchers.Default) { runCatching { saveLogin(pkg, domain, user, pass) }.isSuccess }
                                if (ok) finish() else failed = true
                            } }) { Text(stringResource(R.string.vault_save)) }
                            TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
                        }
                    }
                }
            }
        }
    }

    private fun saveLogin(pkg: String, domain: String?, user: String, pass: String) {
        val now = nowEpoch()
        val certs = AppIdentity.certs(this, pkg)
        VaultSession.update(this, store) { v ->
            val browser = AppIdentity.isBrowser(this, pkg)
            val same = when {
                domain != null -> Match.forWeb(v.entries, domain)
                browser -> emptyList()
                else -> Match.forApp(v.entries, pkg, certs)
            }.firstOrNull { it.username == user }
            val next = (same ?: VaultEntry(title = domain ?: pkg, username = user, url = domain.orEmpty(),
                apps = if (domain == null && !browser) certs.map { AppLink(pkg, it) } else emptyList()))
                .copy(password = pass, modified = now, passwordChanged = now)
            v.copy(entries = v.entries.filterNot { it.uuid == next.uuid } + next)
        }
    }

    companion object { const val PKG = "pkg"; const val DOMAIN = "domain"; const val USER = "user"; const val PASS = "pass" }
}

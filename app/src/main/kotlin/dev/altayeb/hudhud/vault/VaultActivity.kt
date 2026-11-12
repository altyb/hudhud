@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.hudhud.vault

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.altayeb.hudhud.BrandDark
import dev.altayeb.hudhud.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultActivity : ComponentActivity() {
    private val store by lazy { VaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        setContent {
            MaterialTheme(colorScheme = BrandDark) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Falls back to the unlock screen as soon as the timer or the screen-off lock fires.
                    if (VaultSession.unlocked) Home(store) else UnlockGate(store) {}
                }
            }
        }
    }

    override fun onUserInteraction() { if (VaultSession.unlocked) VaultSession.touch(store) }
}

private sealed interface Screen {
    data object List : Screen
    data class Detail(val id: java.util.UUID) : Screen
    data class Edit(val id: java.util.UUID?) : Screen
    data object Health : Screen
    data object Options : Screen
}

@Composable
private fun Home(store: VaultStore) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    if (screen != Screen.List) BackHandler { screen = Screen.List }
    val entries = VaultSession.vault?.entries.orEmpty()
    when (val s = screen) {
        Screen.List -> ListScreen(entries, { screen = it })
        is Screen.Detail -> entries.firstOrNull { it.uuid == s.id }?.let { e -> DetailScreen(e, { screen = Screen.Edit(e.uuid) }, { screen = Screen.List }) } ?: run { screen = Screen.List }
        is Screen.Edit -> EditScreen(store, entries.firstOrNull { it.uuid == s.id }) { screen = Screen.List }
        Screen.Health -> HealthScreen(entries, { screen = Screen.Detail(it) }, { screen = Screen.List })
        Screen.Options -> OptionsScreen(store, { }, { screen = Screen.List })
    }
}

@Composable
private fun ListScreen(entries: List<VaultEntry>, go: (Screen) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(entries, q) {
        entries.filter { e -> q.isBlank() || listOf(e.title, e.username, e.url, e.group).any { it.contains(q.trim(), ignoreCase = true) } }
            .sortedBy { it.title.lowercase() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { go(Screen.Health) }) { Text(stringResource(R.string.vault_health)) }
            TextButton(onClick = { go(Screen.Options) }) { Text(stringResource(R.string.vault_options)) }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vault_search)) })
        if (shown.isEmpty()) Text(stringResource(if (entries.isEmpty()) R.string.vault_empty else R.string.vault_no_results), Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.uuid }) { e -> EntryRow(e) { go(Screen.Detail(e.uuid)) } }
        }
        Button(onClick = { go(Screen.Edit(null)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_add)) }
        TextButton(onClick = { VaultSession.lock() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_lock)) }
    }
}

@Composable
fun EntryRow(e: VaultEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)) {
        Text(e.title.ifEmpty { Match.host(e.url) ?: stringResource(R.string.vault_untitled) }, style = MaterialTheme.typography.titleMedium)
        Text(e.username, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailScreen(e: VaultEntry, edit: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    val otp = remember(e.totp) { Totp.parse(e.totp) }
    var now by remember { mutableLongStateOf(nowEpoch()) }
    if (otp != null) LaunchedEffect(otp) { while (true) { now = nowEpoch(); delay(1000) } }
    val passkey = remember(e.fields) { Passkey.fromFields(e.fields) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(e.title, style = MaterialTheme.typography.headlineSmall)
        if (e.username.isNotEmpty()) CopyRow(R.string.vault_username, e.username) { copySecret(context, "username", e.username) }
        if (e.password.isNotEmpty()) CopyRow(R.string.vault_password, if (reveal) e.password else "••••••••••") { copySecret(context, "password", e.password) }
        if (e.password.isNotEmpty()) TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.vault_hide else R.string.vault_show)) }
        if (otp != null) {
            val code = Totp.code(otp, now)
            CopyRow(R.string.vault_totp, code + "  (" + Totp.secondsLeft(otp, now) + ")") { copySecret(context, "code", code) }
        }
        if (e.url.isNotEmpty()) Labeled(R.string.vault_url, e.url)
        if (e.group.isNotEmpty()) Labeled(R.string.vault_group, e.group)
        if (e.notes.isNotEmpty()) Labeled(R.string.vault_notes, e.notes)
        if (e.apps.isNotEmpty()) Labeled(R.string.vault_linked_apps, e.apps.joinToString("\n") { it.pkg })
        if (passkey != null) Labeled(R.string.vault_passkey, passkey.rpId)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = edit) { Text(stringResource(R.string.vault_edit)) }
            TextButton(onClick = back) { Text(stringResource(R.string.vault_back)) }
        }
    }
}

@Composable
private fun CopyRow(label: Int, value: String, copy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = copy) { Text(stringResource(R.string.vault_copy)) }
    }
}

@Composable
private fun Labeled(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EditScreen(store: VaultStore, old: VaultEntry?, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(old?.title.orEmpty()) }
    var user by remember { mutableStateOf(old?.username.orEmpty()) }
    var pass by remember { mutableStateOf(old?.password.orEmpty()) }
    var url by remember { mutableStateOf(old?.url.orEmpty()) }
    var notes by remember { mutableStateOf(old?.notes.orEmpty()) }
    var totp by remember { mutableStateOf(old?.totp.orEmpty()) }
    var group by remember { mutableStateOf(old?.group.orEmpty()) }
    var apps by remember { mutableStateOf(old?.apps.orEmpty()) }
    var showGen by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val badTotp = totp.isNotBlank() && Totp.parse(totp) == null
    val badUrl = url.isNotBlank() && Match.host(url) == null
    fun write(change: (Vault) -> Vault) {
        busy = true; failed = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) { runCatching { VaultSession.update(context, store, change) }.isSuccess }
            busy = false
            if (ok) done() else failed = true
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vault_entry_title)) })
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vault_username)) })
        SecretField(pass, { pass = it }, R.string.vault_password)
        TextButton(onClick = { showGen = !showGen }) { Text(stringResource(R.string.vault_generate)) }
        if (showGen) GeneratorPanel { pass = it }
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vault_url)) }, isError = badUrl,
            supportingText = if (badUrl) { { Text(stringResource(R.string.vault_bad_url)) } } else null)
        SecretField(totp, { totp = it }, R.string.vault_totp, error = if (badTotp) stringResource(R.string.vault_bad_totp) else null)
        OutlinedTextField(group, { group = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vault_group)) })
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vault_notes)) })
        for (a in apps) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.pkg, Modifier.weight(1f))
            TextButton(onClick = { apps = apps - a }) { Text(stringResource(R.string.vault_unlink)) }
        }
        if (failed) Text(stringResource(R.string.vault_save_failed), color = MaterialTheme.colorScheme.error)
        if (busy) CircularProgressIndicator() else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !badTotp && !badUrl && (title.isNotBlank() || user.isNotBlank() || pass.isNotEmpty()), onClick = {
                val now = nowEpoch()
                val base = old ?: VaultEntry()
                val next = base.copy(title = title.trim(), username = user.trim(), password = pass, url = url.trim(), notes = notes, totp = totp.trim(),
                    group = group.trim().trim('/'), apps = apps, modified = now,
                    passwordChanged = if (old == null || old.password != pass) now else old.passwordChanged)
                write { v -> v.copy(entries = v.entries.filterNot { it.uuid == next.uuid } + next) }
            }) { Text(stringResource(R.string.vault_save)) }
            TextButton(onClick = done) { Text(stringResource(R.string.vault_back)) }
            if (old != null) TextButton(onClick = { askDelete = true }) { Text(stringResource(R.string.vault_delete)) }
        }
    }
    if (askDelete && old != null) AlertDialog(
        onDismissRequest = { askDelete = false },
        text = { Text(stringResource(R.string.vault_delete_confirm, old.title)) },
        confirmButton = { TextButton(onClick = { askDelete = false; write { v -> v.copy(entries = v.entries.filterNot { it.uuid == old.uuid }) } }) { Text(stringResource(R.string.vault_delete)) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun GeneratorPanel(use: (String) -> Unit) {
    val context = LocalContext.current
    var length by remember { mutableFloatStateOf(20f) }
    var symbols by remember { mutableStateOf(true) }
    var noLook by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }
    val words = remember(phrase) { if (phrase) wordList(context) else emptyList() }
    val made = remember(length, symbols, noLook, phrase, seed) {
        if (phrase) Generator.passphrase(words, 6) else Generator.password(GenOptions(length = length.toInt(), symbols = symbols, noLookalikes = noLook))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(made, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(phrase, { phrase = it }); Text("  " + stringResource(R.string.vault_passphrase)) }
        if (!phrase) {
            Text(stringResource(R.string.vault_length, length.toInt()))
            Slider(length, { length = it }, valueRange = 8f..64f)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(symbols, { symbols = it }); Text("  " + stringResource(R.string.vault_symbols)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(noLook, { noLook = it }); Text("  " + stringResource(R.string.vault_no_lookalikes)) }
        }
        Row {
            TextButton(onClick = { seed++ }) { Text(stringResource(R.string.vault_again)) }
            Button(onClick = { use(made) }) { Text(stringResource(R.string.vault_use)) }
        }
    }
}

/** Common English words from the keyboard's own dictionary, so passphrases need no extra file. */
private fun wordList(context: android.content.Context): List<String> =
    context.assets.open("en.tsv").bufferedReader().useLines { lines ->
        lines.map { it.substringBefore('\t').substringBefore(' ') }
            .filter { w -> w.length in 4..8 && w.all { it in 'a'..'z' } }.distinct().take(7776).toList()
    }

@Composable
private fun HealthScreen(entries: List<VaultEntry>, open: (java.util.UUID) -> Unit, back: () -> Unit) {
    val findings = remember(entries) { Audit.run(entries, nowEpoch()) }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.vault_health), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.vault_health_note))
        if (findings.isEmpty()) Text(stringResource(R.string.vault_health_ok))
        LazyColumn(Modifier.weight(1f)) {
            items(findings, key = { it.entry.uuid }) { f ->
                Column(Modifier.fillMaxWidth().clickable { open(f.entry.uuid) }.padding(vertical = 10.dp)) {
                    Text(f.entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(f.issues.map { i -> stringResource(when (i) {
                        Issue.WEAK -> R.string.vault_issue_weak
                        Issue.REUSED -> R.string.vault_issue_reused
                        Issue.OLD -> R.string.vault_issue_old
                        Issue.NO_PASSWORD -> R.string.vault_issue_none
                    }) }.joinToString(" · "), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        TextButton(onClick = back) { Text(stringResource(R.string.vault_back)) }
    }
}

private const val MAX_IMPORT = 64 * 1024 * 1024
private val KDBX_SIG = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())

@Composable
private fun OptionsScreen(store: VaultStore, changed: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var kdbxBytes by remember { mutableStateOf<ByteArray?>(null) }
    var csvToDelete by remember { mutableStateOf<Uri?>(null) }
    var csvExport by remember { mutableStateOf(false) }
    var changePw by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    var lockMin by remember { mutableIntStateOf(store.autoLockMinutes) }
    var bio by remember { mutableStateOf(store.bioEnabled()) }
    var mirror by remember { mutableStateOf(store.mirrorUri != null) }
    val autofill = context.getSystemService(AutofillManager::class.java)
    BackHandler(onBack = back)

    fun job(work: suspend () -> String) {
        busy = true; message = null
        scope.launch {
            message = withContext(Dispatchers.Default) { runCatching { work() }.getOrElse { context.getString(R.string.vault_save_failed) } }
            busy = false
            changed()
        }
    }
    fun readCapped(uri: Uri): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)!!.use { s ->
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                check(out.size() <= MAX_IMPORT) { "too large" }
            }
        }
        return out.toByteArray()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job {
            val data = readCapped(uri)
            if (data.size > 4 && data.copyOf(4).contentEquals(KDBX_SIG)) {
                kdbxBytes = data
                context.getString(R.string.vault_enter_file_password)
            } else {
                val r = runCatching { Csv.import(String(data)) }.getOrNull() ?: return@job context.getString(R.string.vault_bad_file)
                var added = 0
                VaultSession.update(context, store) { v ->
                    val have = v.entries.map { listOf(it.title, it.username, it.password, it.url) }.toSet()
                    val fresh = r.entries.filter { listOf(it.title, it.username, it.password, it.url) !in have }
                    added = fresh.size
                    v.copy(entries = v.entries + fresh)
                }
                csvToDelete = uri
                context.getString(R.string.vault_imported, added, r.skippedRows)
            }
        }
    }
    val saveKdbx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) job {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(VaultSession.export()) }
            context.getString(R.string.vault_exported)
        }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) job {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Csv.exportGoogle(VaultSession.vault!!.entries).toByteArray()) }
            context.getString(R.string.vault_exported_csv)
        }
    }
    val pickMirror = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) job {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(VaultSession.export()) }
            store.mirrorUri = uri.toString()
            mirror = true
            context.getString(R.string.vault_exported)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.vault_options), style = MaterialTheme.typography.headlineSmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) CircularProgressIndicator()
        csvToDelete?.let { uri ->
            Text(stringResource(R.string.vault_csv_delete_note), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                message = context.getString(if (ok) R.string.vault_csv_deleted else R.string.vault_csv_not_deleted)
                csvToDelete = null
            }) { Text(stringResource(R.string.vault_csv_delete)) }
        }
        val enabled = autofill?.hasEnabledAutofillServices() == true
        Text(stringResource(if (enabled) R.string.vault_autofill_on else R.string.vault_autofill_off))
        if (!enabled) TextButton(onClick = {
            context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + context.packageName)))
        }) { Text(stringResource(R.string.vault_autofill_enable)) }
        if (Build.VERSION.SDK_INT >= 34) TextButton(onClick = {
            runCatching { context.startActivity(Intent(AndroidSettings.ACTION_CREDENTIAL_PROVIDER).setData(Uri.parse("package:" + context.packageName))) }
        }) { Text(stringResource(R.string.vault_passkeys_enable)) }
        if (Build.VERSION.SDK_INT >= 28) Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(bio, { on ->
                if (!on) { store.bioDisable(); bio = false }
                else runCatching {
                    biometricPrompt(context, store.bioEncryptCipher()) { c -> VaultSession.storeForBiometric(store, c); bio = true }
                }.onFailure { message = context.getString(R.string.vault_bio_unavailable) }
            })
            Text("  " + stringResource(R.string.vault_fingerprint))
        }
        Text(stringResource(R.string.vault_autolock))
        Row {
            for (m in listOf(1, 5, 15, 60)) FilterChip(lockMin == m, { lockMin = m; store.autoLockMinutes = m; VaultSession.touch(store) },
                { Text(stringResource(R.string.vault_minutes, m)) }, Modifier.padding(end = 6.dp))
        }
        HorizontalDivider()
        TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.vault_import)) }
        Text(stringResource(R.string.vault_import_note), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { saveKdbx.launch("hudhud.kdbx") }) { Text(stringResource(R.string.vault_export_kdbx)) }
        TextButton(onClick = { csvExport = true }) { Text(stringResource(R.string.vault_export_csv)) }
        TextButton(onClick = { if (mirror) { store.mirrorUri = null; mirror = false } else pickMirror.launch("hudhud-sync.kdbx") }) {
            Text(stringResource(if (mirror) R.string.vault_mirror_stop else R.string.vault_mirror_start))
        }
        Text(stringResource(R.string.vault_mirror_note), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        TextButton(onClick = { changePw = true }) { Text(stringResource(R.string.vault_change_master)) }
        TextButton(onClick = { erase = true }) { Text(stringResource(R.string.vault_erase), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = back) { Text(stringResource(R.string.vault_back)) }
    }

    kdbxBytes?.let { bytes ->
        PasswordDialog(R.string.vault_enter_file_password, { kdbxBytes = null }) { pw ->
            kdbxBytes = null
            job {
                val other = try { Kdbx.read(bytes, pw) }
                catch (e: BadPasswordException) { return@job context.getString(R.string.vault_wrong_password) }
                catch (e: CorruptVaultException) { return@job context.getString(R.string.vault_bad_file) }
                VaultSession.update(context, store) { v -> v.copy(entries = Merge.merge(v.entries, other.entries)) }
                context.getString(R.string.vault_merged, other.entries.size)
            }
        }
    }
    if (csvExport) PasswordDialog(R.string.vault_export_csv_warning, { csvExport = false }) { pw ->
        csvExport = false
        if (VaultSession.matches(pw)) saveCsv.launch("hudhud-passwords.csv") else message = context.getString(R.string.vault_wrong_password)
    }
    if (changePw) ChangePasswordDialog({ changePw = false }) { old, new ->
        changePw = false
        if (!VaultSession.matches(old)) message = context.getString(R.string.vault_wrong_password)
        else job { VaultSession.changePassword(context, store, new); bio = false; context.getString(R.string.vault_master_changed) }
    }
    if (erase) PasswordDialog(R.string.vault_erase_warning, { erase = false }) { pw ->
        erase = false
        if (VaultSession.matches(pw)) { VaultSession.lock(); store.erase() } else message = context.getString(R.string.vault_wrong_password)
    }
}

@Composable
private fun PasswordDialog(text: Int, dismiss: () -> Unit, ok: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(text)); SecretField(pw, { pw = it }, R.string.vault_password) } },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { ok(pw.toCharArray()) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(dismiss: () -> Unit, ok: (CharArray, CharArray) -> Unit) {
    var old by remember { mutableStateOf("") }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val short = a.isNotEmpty() && a.length < MIN_MASTER_LENGTH
    AlertDialog(
        onDismissRequest = dismiss,
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField(old, { old = it }, R.string.vault_master_current)
            SecretField(a, { a = it }, R.string.vault_master, error = if (short) stringResource(R.string.vault_master_short, MIN_MASTER_LENGTH) else null)
            SecretField(b, { b = it }, R.string.vault_master_again, error = if (b.isNotEmpty() && a != b) stringResource(R.string.vault_master_differ) else null)
        } },
        confirmButton = { TextButton(enabled = old.isNotEmpty() && a.length >= MIN_MASTER_LENGTH && a == b, onClick = { ok(old.toCharArray(), a.toCharArray()) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

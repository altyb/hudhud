@file:OptIn(ExperimentalMaterial3Api::class)

package dev.altayeb.hudhud

import android.content.Context
import android.Manifest
import android.app.LocaleManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.WbAuto
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val BrandDark = darkColorScheme(
    background = Color(0xFF0B1F25),
    surface = Color(0xFF12292F),
    surfaceContainer = Color(0xFF1A3740),
    primary = Color(0xFFF2A65A),
    onPrimary = Color(0xFF2B1700),
    onBackground = Color(0xFFF2EEE0),
    onSurface = Color(0xFFF2EEE0),
    onSurfaceVariant = Color(0xFFA8BCC0),
    secondary = Color(0xFFA8BCC0),
    secondaryContainer = Color(0xFF2B5663),
    onSecondaryContainer = Color(0xFFF2EEE0),
    surfaceVariant = Color(0xFF2B5663),
    outline = Color(0xFF6F8D94),
)

internal val BrandLight = lightColorScheme(
    background = Color(0xFFF7F4EA),
    surface = Color(0xFFF7F4EA),
    surfaceContainer = Color(0xFFE9E3D1),
    primary = Color(0xFF1C4A5C),
    onPrimary = Color(0xFFF2EEE0),
    onBackground = Color(0xFF12292F),
    onSurface = Color(0xFF12292F),
    onSurfaceVariant = Color(0xFF4F666C),
    secondary = Color(0xFF4F666C),
    secondaryContainer = Color(0xFFCFE0E3),
    onSecondaryContainer = Color(0xFF12292F),
    surfaceVariant = Color(0xFFCFE0E3),
    outline = Color(0xFF7A9399),
)

/** Bumped on every onResume so the setup card re-checks whether Hudhud is on after the system screens. */
private val resumes = mutableIntStateOf(0)

/** Settings screen. Only this Activity uses Compose; the keyboard service stays dependency-free. */
class Settings : ComponentActivity() {
    override fun onResume() { super.onResume(); resumes.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) BrandDark else BrandLight) {
                SettingsScreen(prefs)
            }
        }
    }
}

@Composable
private fun SettingsScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val d = remember { Prefs() }
    val toggles = remember { mutableStateMapOf<String, Boolean>() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val done = stringResource(R.string.done)
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var typed by remember { mutableStateOf("") }
    var height by remember { mutableIntStateOf(prefs.getInt("keyHeight", d.keyHeight).coerceIn(80, 140)) }
    var hand by remember { mutableIntStateOf(prefs.getInt("oneHanded", d.oneHanded)) }
    var page by remember { mutableIntStateOf(0) }
    BackHandler(page != 0) { page = 0 }
    var askLicenses by remember { mutableStateOf(false) }
    var askWords by remember { mutableStateOf(false) }
    var askClips by remember { mutableStateOf(false) }

    fun flag(key: String, def: Boolean) = toggles[key] ?: prefs.getBoolean(key, def)
    fun setFlag(key: String, on: Boolean) {
        toggles[key] = on
        prefs.edit().putBoolean(key, on).apply()
    }
    fun wipe(key: String) {
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        scope.launch { snack.showSnackbar(done) }
    }
    fun tell(msg: Int) { val text = context.getString(msg); scope.launch { snack.showSnackbar(text) } }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { Backup.write(it, java.io.File(context.filesDir, "user.dict"), prefs.all) }
            }.isSuccess
            tell(if (ok) R.string.backup_saved else R.string.backup_failed)
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val data = context.contentResolver.openInputStream(uri)!!.use { Backup.read(it, java.io.File(context.cacheDir, "restore.dict")) }
                val edit = prefs.edit()
                Backup.applyPrefs(data.prefs, edit)
                data.dict?.let { java.nio.file.Files.move(it.toPath(), java.io.File(context.filesDir, "user.dict").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                edit.putLong(Backup.RELOAD_KEY, System.currentTimeMillis()).apply()
            }.isSuccess
            tell(if (ok) R.string.backup_restored else R.string.backup_bad_file)
        }
    }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setFlag("contacts", it) // denied keeps it off
    }

    @Composable
    fun Flag(key: String, def: Boolean, title: Int, sub: Int) =
        SwitchRow(title, sub, flag(key, def)) { setFlag(key, it) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(PAGE_TITLES[page])) },
                navigationIcon = { if (page != 0) IconButton(onClick = { page = 0 }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page == 0) item {
                val imm = context.getSystemService(InputMethodManager::class.java)
                val enabled = resumes.intValue >= 0 && imm.enabledInputMethodList.any { it.packageName == context.packageName }
                val chosen = resumes.intValue >= 0 && android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                    .orEmpty().startsWith(context.packageName + "/")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Image(painterResource(R.drawable.ic_hudhud_mark), null, Modifier.size(64.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
                        Text(
                            stringResource(if (!enabled) R.string.status_enable else if (!chosen) R.string.status_choose else R.string.status_on),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (!enabled) Button(
                        onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.enable_keyboard)) }
                    else if (!chosen) Button(
                        onClick = { imm.showInputMethodPicker() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.choose_keyboard)) }
                    else FilledTonalButton(
                        onClick = { imm.showInputMethodPicker() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.choose_keyboard)) }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = { Text(stringResource(R.string.try_typing)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (page == 0) {
                item { HubRow(Icons.Rounded.Keyboard, R.string.page_typing, R.string.page_typing_sub) { page = 1 } }
                item { HubRow(Icons.Rounded.Palette, R.string.page_look, R.string.page_look_sub) { page = 2 } }
                item { HubRow(Icons.Rounded.Translate, R.string.page_languages, R.string.page_languages_sub) { page = 3 } }
                item { HubRow(Icons.Rounded.GridView, R.string.page_layout, R.string.page_layout_sub) { page = 4 } }
                item { HubRow(Icons.Rounded.Lock, R.string.page_privacy, R.string.page_privacy_sub) { page = 5 } }
                item { HubRow(Icons.Rounded.Key, R.string.page_passwords, R.string.page_passwords_sub) { page = 6 } }
                item { HubRow(Icons.Rounded.Info, R.string.page_about, R.string.page_about_sub) { page = 7 } }
            } else item { TipCard(PAGE_TIPS[page]) }
            if (page == 1) {
            item {
                Group(R.string.group_smart) {
                    Flag("suggestions", d.suggestions, R.string.suggestions, R.string.suggestions_sub)
                    Flag("predictNext", d.predictNext, R.string.predict_next, R.string.predict_next_sub)
                    Flag("autocorrect", d.autocorrect, R.string.autocorrect, R.string.autocorrect_sub)
                    Flag("learn", d.learn, R.string.learn, R.string.learn_sub)
                    SwitchRow(R.string.contacts, R.string.contacts_sub, flag("contacts", d.contacts)) {
                        if (it) contacts.launch(Manifest.permission.READ_CONTACTS) else setFlag("contacts", false)
                    }
                }
            }
            item {
                Group(R.string.group_typing) {
                    Flag("glide", d.glide, R.string.glide, R.string.glide_sub)
                    Flag("swipeEdit", d.swipeEdit, R.string.swipe_edit, R.string.swipe_edit_sub)
                    Flag("autoCaps", d.autoCaps, R.string.auto_caps, R.string.auto_caps_sub)
                    Flag("doubleSpacePeriod", d.doubleSpacePeriod, R.string.double_space, R.string.double_space_sub)
                    Flag("vibrate", d.vibrate, R.string.vibrate, R.string.vibrate_sub)
                    Flag("sound", d.sound, R.string.sound, R.string.sound_sub)
                }
            }
            }
            if (page == 2) {
            item { LookGroup(d) }
            }
            if (page == 3) {
            item {
                Group(R.string.group_languages) { LanguageChoice(prefs, d.languages) }
            }
            if (Build.VERSION.SDK_INT >= 33) item { LanguageGroup() }
            }
            if (page == 4) {
            item {
                Group(R.string.group_layout) {
                    Flag("split", d.split, R.string.split, R.string.split_sub)
                    SwitchRow(R.string.one_handed, R.string.one_handed_sub, hand != 0) {
                        hand = if (it) 1 else 0
                        prefs.edit().putInt("oneHanded", hand).apply()
                    }
                    Flag("numberRow", d.numberRow, R.string.number_row, R.string.number_row_sub)
                    Flag("hints", d.hints, R.string.hints, R.string.hints_sub)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.key_height), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.percent, height),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Slider(
                            value = height.toFloat(),
                            onValueChange = {
                                height = it.roundToInt()
                                prefs.edit().putInt("keyHeight", height).apply()
                            },
                            valueRange = 80f..140f,
                            steps = 11,
                        )
                    }
                }
            }
            }
            if (page == 5) {
            item {
                Group(R.string.group_clipboard) {
                    Flag("clipboard", d.clipboard, R.string.clipboard, R.string.clipboard_sub)
                }
            }
            item {
                Group(R.string.group_privacy) {
                    Flag("incognito", d.incognito, R.string.incognito, R.string.incognito_sub)
                    Flag("clearClipsOnLock", d.clearClipsOnLock, R.string.clear_on_lock, R.string.clear_on_lock_sub)
                    Flag("secureKeyboard", d.secureKeyboard, R.string.secure_keyboard, R.string.secure_keyboard_sub)
                    ActionRow(R.string.backup_save) { saveBackup.launch("hudhud-backup.zip") }
                    ActionRow(R.string.backup_restore) { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) }
                    ActionRow(R.string.clear_words, danger = true) { askWords = true }
                    ActionRow(R.string.clear_clips, danger = true) { askClips = true }
                }
            }
            }
            if (page == 6) {
            item {
                Group(R.string.vault_group_settings) {
                    ActionRow(R.string.vault_open) { context.startActivity(Intent(context, dev.altayeb.hudhud.vault.VaultActivity::class.java)) }
                    Flag("vaultChip", d.vaultChip, R.string.vault_key_chip, R.string.vault_key_chip_sub)
                }
            }
            }
            if (page == 7) {
            item {
                Group(R.string.group_about) {
                    ActionRow(R.string.source_code) { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/altyb/hudhud"))) }
                    ActionRow(R.string.licenses) { askLicenses = true }
                    Text(
                        stringResource(R.string.about_text),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.credit_text),
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.version, version),
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
        }
    }

    if (askLicenses) {
        val text = remember {
            runCatching { listOf("NOTICE.txt", "LICENSE.txt").joinToString("\n\n") { n -> context.assets.open(n).bufferedReader().use { it.readText() } } }.getOrDefault("")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askLicenses = false },
            title = { Text(stringResource(R.string.licenses)) },
            text = { Text(text, Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askLicenses = false }) { Text(stringResource(R.string.licenses_close)) } },
        )
    }
    if (askWords) ConfirmDialog(R.string.confirm_words_title, R.string.confirm_words_text,
        onConfirm = { wipe("wipeLearnedAt"); askWords = false }, onDismiss = { askWords = false })
    if (askClips) ConfirmDialog(R.string.confirm_clips_title, R.string.confirm_clips_text,
        onConfirm = { wipe("wipeClipsAt"); askClips = false }, onDismiss = { askClips = false })
}

private val PAGE_TITLES = intArrayOf(R.string.app_name, R.string.page_typing, R.string.page_look, R.string.page_languages,
    R.string.page_layout, R.string.page_privacy, R.string.page_passwords, R.string.page_about)
private val PAGE_TIPS = intArrayOf(0, R.string.tip_typing, R.string.tip_look, R.string.tip_languages,
    R.string.tip_layout, R.string.tip_privacy, R.string.tip_passwords, R.string.tip_about)

/** One short guide at the top of each page: the mascot and a sentence on what the page does. */
@Composable
private fun TipCard(text: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(painterResource(R.drawable.ic_hudhud_mark), null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of the main list: round glyph, title, one line saying what is inside. */
@Composable
private fun HubRow(icon: ImageVector, title: Int, sub: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Colours and motion of the keyboard: any surface with any accent. Sizes never change. */
@Composable
private fun LookGroup(d: Prefs) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    var base by remember { mutableStateOf(prefs.getString("base", d.base) ?: d.base) }
    var accent by remember { mutableIntStateOf(prefs.getInt("accent", d.accent)) }
    var anim by remember { mutableStateOf(prefs.getBoolean("animations", d.animations)) }
    val shown = KeyTheme.of(ctx, base, accent)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyPreview(shown)
        Group(R.string.look_surface) {
            Swatches {
                KeyTheme.BASES.forEach { (id, b) ->
                    val sel = id == base
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(b.bg))
                            .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .clickable { base = id; prefs.edit().putString("base", id).apply() },
                    ) {
                        Box(Modifier.align(Alignment.Center).size(width = 28.dp, height = 16.dp).clip(RoundedCornerShape(3.dp)).background(Color(b.key)))
                    }
                }
            }
        }
        Group(R.string.accent) {
            Swatches {
                KeyTheme.ACCENTS.forEach { c ->
                    val sel = c == accent
                    val fill = if (c == 0) Color.Gray else Color(c)
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(fill)
                            .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(20.dp)) else Modifier)
                            .clickable { accent = c; prefs.edit().putInt("accent", c).apply() },
                        contentAlignment = Alignment.Center,
                    ) { if (c == 0) Icon(Icons.Rounded.WbAuto, null, tint = Color.White) }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) Text(
                stringResource(R.string.accent_auto), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Group(R.string.group_look) {
            ToneChoice(prefs)
            SwitchRow(R.string.animations, R.string.animations_sub, anim) {
                anim = it; prefs.edit().putBoolean("animations", it).apply()
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Swatches(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** Three rows of keys in the chosen colours, so a choice shows at once. */
@Composable
private fun KeyPreview(t: KeyTheme) {
    fun c(v: Int) = Color(v)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c(t.bg)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("q w e r t y u i o p", "a s d f g h j k l", "⇧ z x c v b n m ⌫").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.split(" ").forEach { k ->
                    val special = k == "⇧" || k == "⌫"
                    Box(
                        Modifier.weight(if (special) 1.4f else 1f).height(34.dp).clip(RoundedCornerShape(t.cornerDp.dp))
                            .background(c(if (special) t.special else t.key)),
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = c(if (special) t.specialLabel else t.label), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1.4f).height(34.dp).clip(RoundedCornerShape(t.cornerDp.dp)).background(c(t.special)), contentAlignment = Alignment.Center) { Text("123", color = c(t.specialLabel), style = MaterialTheme.typography.labelLarge) }
            Box(Modifier.weight(5f).height(34.dp).clip(RoundedCornerShape(t.cornerDp.dp)).background(c(t.key)))
            Box(Modifier.weight(1.4f).height(34.dp).clip(RoundedCornerShape(t.cornerDp.dp)).background(c(t.accent)), contentAlignment = Alignment.Center) { Text("↵", color = c(t.onAccent), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Languages the space bar switches between. At least one always stays on. */
@Composable
private fun LanguageChoice(prefs: SharedPreferences, default: String) {
    var on by remember { mutableStateOf(Layouts.enabled(prefs.getString("languages", default) ?: default)) }
    Layouts.ALL.forEach { (id, name) ->
        Row(
            Modifier.fillMaxWidth().clickable {
                val next = if (id in on) on - id else on + id
                if (next.isNotEmpty()) {
                    on = next
                    prefs.edit().putString("languages", next.joinToString(",")).apply()
                }
            }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Checkbox(checked = id in on, onCheckedChange = null)
        }
    }
}

@Composable
private fun ToneChoice(prefs: SharedPreferences) {
    var tone by remember { mutableIntStateOf(prefs.getInt("emojiTone", 0)) }
    val hands = listOf("👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿")
    Text(stringResource(R.string.emoji_tone), Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        hands.forEachIndexed { i, h ->
            Text(
                h,
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (tone == i) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { tone = i; prefs.edit().putInt("emojiTone", i).apply() }.padding(6.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun LanguageGroup() {
    val lm = LocalContext.current.getSystemService(LocaleManager::class.java)
    val tags = listOf("", "en", "ar")
    val labels = listOf(R.string.lang_system, R.string.lang_en, R.string.lang_ar)
    var sel by remember {
        mutableIntStateOf(tags.indexOf(lm.applicationLocales.toLanguageTags().substringBefore('-')).coerceAtLeast(0))
    }
    Group(R.string.group_language) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(8.dp)) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = sel == i,
                    onClick = {
                        sel = i
                        lm.applicationLocales = LocaleList.forLanguageTags(tags[i])
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

@Composable
private fun Group(title: Int, content: @Composable ColumnScope.() -> Unit) {
    // Same entrance as the keyboard's panels: Motion.NORMAL, decelerate, small rise.
    val ctx = LocalContext.current
    val motion = remember { Motion.enabled(Prefs.read(ctx).animations) }
    val shown = remember { MutableTransitionState(!motion) }.apply { targetState = true }
    val spec = tween<Float>(Motion.NORMAL.toInt(), easing = EnterEasing)
    AnimatedVisibility(
        shown,
        enter = fadeIn(spec) + slideInVertically(tween(Motion.NORMAL.toInt(), easing = EnterEasing)) { it / 10 },
    ) { GroupBody(title, content) }
}

/** Motion.DECELERATE as a Compose easing. */
private val EnterEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

@Composable
private fun GroupBody(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            stringResource(title),
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun SwitchRow(title: Int, sub: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onChange(!checked) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).animateContentSize(spring()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(sub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            thumbContent = if (checked) { { Text("✓", style = MaterialTheme.typography.labelSmall) } } else null,
        )
    }
}

@Composable
private fun ActionRow(title: Int, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        stringResource(title),
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(16.dp),
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ConfirmDialog(title: Int, text: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

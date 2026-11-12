package dev.altayeb.hudhud

import android.Manifest
import android.content.ClipDescription
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.ContactsContract
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import java.io.File

/**
 * Glue: key presses → text, text → engine (worker thread) → suggestion strip.
 * Main thread owns views and the input connection; the worker owns the tries, engines and user dictionary.
 */
class KeyboardService : InputMethodService(), KeyboardView.Listener, SuggestionStrip.Listener, EmojiPanel.Listener,
    ClipPanel.Listener, EmojiSearchBar.Listener {

    private lateinit var keys: KeyboardView
    private lateinit var strip: SuggestionStrip
    private lateinit var root: LinearLayout
    private var clipPanel: ClipPanel? = null
    private var clipChip = ""        // strip text standing for a fresh copy; picking it pastes [clipChipText]
    private var clipChipText = ""
    private var clipChipUsedId = 0L  // a copy offered once as a chip and pasted is not offered again
    private var emojiPanel: EmojiPanel? = null

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("hudhud-engine").apply { start() }
    private val worker = Handler(workerThread.looper)

    // Worker-only state.
    private val engines = HashMap<String, Engine>()
    private lateinit var userDict: UserDict

    // Main-thread state.
    private var prefs = Prefs()
    // Before Android 13 password managers can't mark copies as sensitive, so history lives 5 min there.
    private val clips = ClipHistory(ttlMs = if (Build.VERSION.SDK_INT >= 33) 3_600_000L else 300_000L)
    private var lang = "en"
    private var layout: Layout? = null
    private var shift = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var incognito = false    // this field: password / no-learning flag / incognito switch
    private var password = false
    private var noSuggestions = false
    private var vaultChip = false
    private var vaultExpected = false
    private var vaultTarget = ""
    private var inlineBar: android.widget.LinearLayout? = null
    private var best = ""            // what space inserts when autoCorrect is on
    private var bestFor = ""         // the typed word [best] was computed for; space checks it still matches
    private var autoCorrect = false
    private var lastCorrection: Pair<String, String>? = null  // original → corrected, for undo by backspace
    private var lastGlide = ""       // word the last glide typed; backspace removes it whole, a new word gets a space
    private var searchBar: EmojiSearchBar? = null
    private var searching = false
    @Volatile private var generation = 0

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    override fun onCreate() {
        super.onCreate()
        lang = getSharedPreferences(Prefs.FILE, MODE_PRIVATE).getString("lang", "en") ?: "en"
        worker.post {
            userDict = UserDict(File(filesDir, "user.dict")).also { it.load() }
        }
        clipboard.addPrimaryClipChangedListener(clipListener)
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(wipeListener)
        try { clips.loadPinned(pinnedFile) } catch (e: java.io.IOException) {}
        val screenOff = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOffReceiver, screenOff, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOffReceiver, screenOff)
    }

    /** Screen locked: forget unpinned clipboard history if the user wants that. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Prefs.read(c).clearClipsOnLock) return
            clips.clearUnpinned()
            if (clipPanel?.parent != null) hideClipPanel()
        }
    }

    private val pinnedFile by lazy { File(filesDir, "pinned.txt") }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(wipeListener)
        unregisterReceiver(screenOffReceiver)
        worker.post { saveDict() }
        workerThread.quitSafely()
        super.onDestroy()
    }

    /** Worker only. A language's dictionary is built the first time it is used, so unused ones cost nothing. */
    private fun engine(l: String): Engine = engines.getOrPut(l) { Engine(openTrie(l), userDict) }

    /** Dictionary file is built once from the bundled word list, then memory-mapped on every start. */
    private fun openTrie(l: String): Trie {
        val file = File(filesDir, "$l.dict")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val stamp = File(filesDir, "$l.dict.version")
        fun build() {
            // Word pairs (from Tatoeba) give next-word predictions before the user has typed anything.
            val pairs = try { assets.open("$l.bigrams").bufferedReader().readLines() } catch (e: java.io.IOException) { emptyList() }
            assets.open("$l.tsv").bufferedReader().useLines { Trie.build(it, pairs.asSequence(), file) }
            stamp.writeText(version.toString())
        }
        if (!file.exists() || stamp.takeIf { it.exists() }?.readText() != version.toString()) build()
        return try {
            Trie.open(file)
        } catch (e: java.io.IOException) {
            build() // damaged (disk full, killed mid-write): rebuild once
            Trie.open(file)
        }
    }

    /** Logins from the autofill service appear here. The system draws them, so this keyboard never sees their contents. */
    @androidx.annotation.RequiresApi(30)
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest {
        val d = resources.displayMetrics.density
        val style = androidx.autofill.inline.UiVersions.newStylesBuilder()
            .addStyle(androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder().build()).build()
        val spec = android.widget.inline.InlinePresentationSpec.Builder(
            android.util.Size((100 * d).toInt(), (40 * d).toInt()), android.util.Size((260 * d).toInt(), (40 * d).toInt()),
        ).setStyle(style).build()
        return android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(5).build()
    }

    @androidx.annotation.RequiresApi(30)
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        val bar = inlineBar ?: return false
        bar.removeAllViews()
        val d = resources.displayMetrics.density
        val size = android.util.Size(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (40 * d).toInt())
        for (s in response.inlineSuggestions) s.inflate(this, size, mainExecutor) { v -> if (v != null) bar.addView(v) }
        return true
    }

    override fun onCreateInputView(): View {
        keys = KeyboardView(this).also { it.listener = this }
        strip = SuggestionStrip(this).also { it.listener = this }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            if (Build.VERSION.SDK_INT >= 30) {
                val bar = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                inlineBar = bar
                addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(bar) })
            }
            addView(strip)
            addView(keys)
            // Edge-to-edge (Android 15+): keep the keys above the gesture bar / nav buttons.
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { v, insets ->
                // Some skins report 0 first and the real size on a later pass, or only as a gesture inset: take the largest.
                v.setPadding(0, 0, 0, maxOf(
                    insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(android.view.WindowInsets.Type.systemGestures()).bottom))
                insets
            }
        }
        return root
    }

    // Not in onStartInputView: coming back from the picker, the keys may stay hidden while the field already has focus.
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        takeVaultValue(attribute)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        prefs = Prefs.read(this)
        applyLook()
        root.requestApplyInsets()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        password = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        // Our own screens hold the vault's names and notes, so nothing typed there is ever learned.
        incognito = password || prefs.incognito || info.packageName == packageName || info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        // Screen recorders and screenshots see a black keyboard while a password is typed.
        window?.window?.let {
            if (password && prefs.secureKeyboard) it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        applyWipeRequests()
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        noSuggestions = password || numeric || !prefs.suggestions ||
            cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        vaultChip = prefs.vaultChip && (password || cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        vaultTarget = info.packageName.orEmpty()
        strip.visibility = if (noSuggestions && !vaultChip) View.GONE else View.VISIBLE
        if (vaultChip) strip.show(getString(R.string.vault_chip), emptyList(), false)
        // A restart is the same field redoing its setup (Compose does it on cut); an open panel stays.
        if (!restarting) {
            hideClipPanel()
            hideEmoji()
            stopEmojiSearch()
        }
        // Nothing from the previous field may act in this one (a stale autocorrect could rewrite a password).
        autoCorrect = false
        best = ""
        bestFor = ""
        lastCorrection = null
        lastGlide = ""
        capsLock = false
        keys.hand = prefs.oneHanded
        keys.split = prefs.split && resources.configuration.screenWidthDp >= 600
        if (lang !in Layouts.enabled(prefs.languages)) lang = Layouts.enabled(prefs.languages)[0]
        keys.swipeEdit = prefs.swipeEdit
        applyLayout(if (numeric) "numbers" else lang)
        if (prefs.contacts) loadContacts() else worker.post { userDict.setContacts(emptyList()) }
        updateShift()
        requestSuggestions()
    }

    override fun onFinishInput() {
        inlineBar?.removeAllViews()
        super.onFinishInput()
        worker.post { if (::userDict.isInitialized) saveDict() }
    }

    // ---- keys ----

    override fun onKey(code: Int) {
        val ic = currentInputConnection ?: return
        if (prefs.sound) getSystemService(AudioManager::class.java)
            .playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        if (searching) { searchKey(code); return }
        val undo = lastCorrection
        lastCorrection = null
        val glided = lastGlide
        lastGlide = ""
        when (code) {
            Codes.DELETE -> {
                if (glided.isNotEmpty() && ic.getTextBeforeCursor(glided.length, 0)?.toString() == glided) {
                    ic.deleteSurroundingText(glided.length, 0)
                } else if (undo != null) {
                    ic.deleteSurroundingText(undo.second.length + 1, 0)
                    ic.commitText(undo.first, 1)
                } else if (ic.getSelectedText(0).isNullOrEmpty()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                } else {
                    ic.commitText("", 1)
                }
            }
            Codes.SHIFT -> {
                val now = System.currentTimeMillis()
                when {
                    capsLock -> { capsLock = false; shift = false }
                    shift && now - lastShiftTap < 300 -> capsLock = true // double tap = caps lock
                    else -> shift = !shift
                }
                lastShiftTap = now
                keys.shifted = shift
                return
            }
            Codes.ENTER -> {
                val action = currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                val noAction = currentInputEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
            Codes.SPACE -> onSpace(ic)
            Codes.SYMBOLS -> { applyLayout(if (lang == "ar") "symbols-ar" else "symbols"); return }
            Codes.LETTERS -> { applyLayout(lang); return }
            Codes.LANG -> nextLanguage(1)
            Codes.CLIPBOARD -> { showClipPanel(); return }
            Codes.EMOJI -> { showEmoji(); return }
            else -> {
                val text = String(Character.toChars(code))
                // "@", ":" and "/" start emails and links: the word before them is not learned.
                if (!Character.isLetterOrDigit(code) && code != '\''.code && code.toChar() !in "@:/") learnLastWord()
                autoCorrect = false // the next result decides again; a stale one must not fire on space
                if (glided.isNotEmpty() && Character.isLetterOrDigit(code)) ic.commitText(" ", 1)
                ic.commitText(if (shift) text.uppercase() else text, 1)
                if (shift && !capsLock) { shift = false; keys.shifted = false }
            }
        }
        updateShift()
        requestSuggestions()
    }

    override fun onSpecialLongPress(code: Int) {
        if (code == Codes.SPACE) {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
        } else if (code == Codes.EMOJI) {
            onHand(if (prefs.oneHanded != 0) 0 else 1)
        } else {
            onToggles()
        }
    }

    override fun onSwipeSpace(dir: Int) {
        if (layout?.id in Layouts.ALL || layout?.id?.startsWith("symbols") == true) nextLanguage(dir)
    }

    override fun onCursor(dir: Int) {
        lastGlide = ""
        sendDownUpKeyEvents(if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        updateShift()
        requestSuggestions()
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        lastGlide = ""
        lastCorrection = null
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        ic.deleteSurroundingText(wordDeleteLength(before), 0)
        updateShift()
        requestSuggestions()
    }

    override fun onHand(side: Int) {
        prefs = prefs.copy(oneHanded = side)
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit().putInt("oneHanded", side).apply()
        keys.hand = side
    }

    override fun onGlide(path: FloatArray, centers: Map<Char, List<FloatArray>>) {
        val gen = ++generation
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val engineLang = layout?.id ?: return
        val prev = WORD.findAll(before).lastOrNull()?.value.orEmpty()
        worker.post {
            val words = try {
                engine(engineLang).glide(Glide(path, centers), prev)
            } catch (e: java.io.IOException) {
                emptyList()
            } catch (e: RuntimeException) {
                emptyList() // damaged dictionary: nothing typed rather than a dead keyboard
            }
            main.post { if (gen == generation) commitGlide(words) }
        }
    }

    private fun commitGlide(words: List<String>) {
        val ic = currentInputConnection ?: return
        if (words.isEmpty()) return
        val cased = words.map { if (capsLock) it.uppercase() else if (shift) it.replaceFirstChar(Char::uppercase) else it }
        val word = cased[0]
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isNotEmpty() && before[0].isLetterOrDigit()) {
            learnLastWord()
            ic.commitText(" ", 1)
        }
        ic.commitText(word, 1)
        lastGlide = word
        lastCorrection = null
        bestFor = word
        best = word
        autoCorrect = false
        if (shift && !capsLock) { shift = false; keys.shifted = false }
        updateShift()
        // The other guesses stay on the strip: picking one replaces the word.
        strip.show(word, cased.drop(1), layout?.rtl == true)
    }

    override fun onLongPress(text: String) {
        if (searching) { searchText(text); return }
        currentInputConnection?.commitText(if (shift) text.uppercase() else text, 1)
        if (shift && !capsLock) { shift = false; keys.shifted = false }
        updateShift()
        requestSuggestions()
    }

    private fun nextLanguage(dir: Int) {
        val list = Layouts.enabled(prefs.languages)
        lang = list[(list.indexOf(lang).coerceAtLeast(0) + dir + list.size) % list.size]
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit().putString("lang", lang).apply()
        // Swiping on the symbols page keeps the symbols page; only the language changes.
        applyLayout(if (layout?.id?.startsWith("symbols") == true) (if (lang == "ar") "symbols-ar" else "symbols") else lang)
        updateShift()
        requestSuggestions()
    }

    private fun onSpace(ic: android.view.inputmethod.InputConnection) {
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (prefs.doubleSpacePeriod && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            return
        }
        val typed = currentWord()
        if (autoCorrect && prefs.autocorrect && typed.isNotEmpty() && typed == bestFor && best.isNotEmpty() && best != typed) {
            ic.deleteSurroundingText(typed.length, 0)
            ic.commitText("$best ", 1)
            lastCorrection = typed to best
        } else {
            ic.commitText(" ", 1)
        }
        learnLastWord()
    }

    // ---- suggestions ----

    /** Password text goes only into password fields, directly, so the engine, the learner and the undo state never see it. */
    private fun takeVaultValue(info: EditorInfo) {
        if (!vaultExpected) return
        val pkg = info.packageName ?: return
        if (pkg == packageName) return // still in the picker's own search box
        vaultExpected = false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val inPasswordField = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName, binder: android.os.IBinder) {
                val self = this
                val reply = android.os.Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(m: android.os.Message) {
                        val text = m.data?.getString("text")
                        if (text != null && (m.what != dev.altayeb.hudhud.vault.FillActivity.BRIDGE_PASSWORD || inPasswordField)) currentInputConnection?.commitText(text, 1)
                        runCatching { unbindService(self) }
                    }
                })
                val ask = android.os.Message.obtain().apply { replyTo = reply; data = android.os.Bundle().apply { putString(dev.altayeb.hudhud.vault.FillActivity.PKG, pkg) } }
                runCatching { android.os.Messenger(binder).send(ask) }.onFailure { runCatching { unbindService(self) } }
            }
            override fun onServiceDisconnected(n: android.content.ComponentName) {}
        }
        bindService(Intent(this, dev.altayeb.hudhud.vault.VaultBridgeService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onPick(word: String) {
        val ic = currentInputConnection ?: return
        if (vaultChip && word == getString(R.string.vault_chip)) {
            vaultExpected = true
            startActivity(Intent(this, dev.altayeb.hudhud.vault.FillActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                .putExtra(dev.altayeb.hudhud.vault.FillActivity.KEYBOARD, true)
                .putExtra(dev.altayeb.hudhud.vault.FillActivity.PKG, vaultTarget))
            return
        }
        lastGlide = ""
        if (clipChip.isNotEmpty() && word == clipChip) {
            ic.commitText(clipChipText, 1)
            clips.fresh(CHIP_MS)?.let { clipChipUsedId = it.id }
            requestSuggestions()
            return
        }
        val typed = currentWord()
        if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        learnLastWord()
        updateShift()
        requestSuggestions()
    }

    override fun onClipboard() = showClipPanel()

    override fun onToggles() {
        startActivity(Intent(this, Settings::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ask the worker for suggestions; any older pending job sees a newer generation and does nothing. */
    private fun requestSuggestions() {
        if (noSuggestions || !::strip.isInitialized) return
        val gen = ++generation
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val engineLang = layout?.takeIf { it.id in Layouts.ALL }?.id ?: lang
        val ac = prefs.autocorrect
        val next = prefs.predictNext
        worker.post {
            if (gen != generation) return@post
            val result = try {
                engine(engineLang).suggest(before, ac, next)
            } catch (e: java.io.IOException) {
                Suggestions.EMPTY // this language's dictionary cannot be read
            } catch (e: RuntimeException) {
                Suggestions.EMPTY // damaged dictionary: no suggestions rather than a dead keyboard
            }
            main.post { if (gen == generation) showSuggestions(before, result) }
        }
    }

    private fun showSuggestions(before: String, s: Suggestions) {
        val typed = trailingWord(before)
        bestFor = typed
        autoCorrect = s.autoCorrect
        best = if (s.autoCorrect) s.words.firstOrNull().orEmpty() else typed
        val fresh = if (typed.isEmpty() && prefs.clipboard) clips.fresh(CHIP_MS)?.takeIf { it.id != clipChipUsedId } else null
        clipChipText = fresh?.text.orEmpty()
        clipChip = fresh?.let { it.text.replace('\n', ' ').trim().take(18) + if (it.text.length > 18) "…" else "" }.orEmpty()
        val middle = when {
            clipChip.isNotEmpty() -> clipChip
            best.isEmpty() -> s.words.firstOrNull().orEmpty()
            else -> best
        }
        val others = s.words.filter { it != middle }
        strip.show(middle, others, layout?.rtl == true)
    }

    // ---- learning ----

    private fun learnLastWord() {
        if (incognito || noSuggestions || !prefs.learn) return
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        // Whole tokens: anything with a digit or @ / : (codes, card numbers, emails, links) is never learned.
        val tokens = before.split(SPACES).filter { it.isNotEmpty() }
        val token = tokens.lastOrNull() ?: return
        if (token.any { it.isDigit() || it == '@' || it == '/' || it == ':' }) return
        val word = WORD.findAll(token).map { it.value }.lastOrNull() ?: return
        if (!before.trimEnd().endsWith(word) && !before.trimEnd().dropLast(1).endsWith(word)) return
        val prev = tokens.getOrNull(tokens.size - 2)
            ?.takeIf { t -> t.none { it.isDigit() || it == '@' || it == '/' || it == ':' } }
            ?.let { WORD.findAll(it).map { m -> m.value }.lastOrNull() }
        worker.post { userDict.learn(word, prev) }
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        worker.post {
            val names = ArrayList<String>()
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
            )?.use { c -> while (c.moveToNext() && names.size < UserDict.MAX_CONTACTS) c.getString(0)?.let(names::add) }
            userDict.setContacts(names)
        }
    }

    // ---- clipboard ----

    private fun onClipChanged() {
        if (!prefs.clipboard || incognito) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        val item = clip.getItemAt(0)
        if (item.uri != null && clip.description.hasMimeType("image/*")) {
            clips.add(getString(R.string.clip_image), sensitive, item.uri.toString())
            return
        }
        val text = item.coerceToText(this)?.toString() ?: return
        clips.add(text, sensitive)
        requestSuggestions() // shows the paste chip
    }

    private fun showClipPanel() {
        hideEmoji()
        val panel = clipPanel ?: ClipPanel(this, keys.height.coerceAtLeast(1)).also {
            it.listener = this
            clipPanel = it
        }
        panel.theme = theme
        panel.animations = Motion.enabled(prefs.animations)
        panel.show(if (prefs.clipboard) clips.items() else emptyList(), System.currentTimeMillis())
        if (panel.parent == null) { root.addView(panel); slideIn(panel) }
        keys.visibility = View.GONE
    }

    private fun hideClipPanel() {
        clipPanel?.let { root.removeView(it) }
        if (::keys.isInitialized && emojiPanel?.parent == null) keys.visibility = View.VISIBLE
    }

    override fun onClipPaste(clip: Clip) {
        val image = clip.uri
        if (image != null) {
            // Only the app being typed in can insert an image, by pasting. Fails quietly if the source took back access.
            runCatching {
                clipboard.setPrimaryClip(android.content.ClipData.newUri(contentResolver, clip.text, android.net.Uri.parse(image)))
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }
        } else currentInputConnection?.commitText(clip.text, 1)
        hideClipPanel()
        requestSuggestions()
    }

    override fun onClipPin(clip: Clip, pinned: Boolean) {
        if (clip.uri != null) return // an image's address does not survive a restart, so it cannot be kept
        clips.pin(clip.id, pinned)
        savePins()
        showClipPanel()
    }

    override fun onClipDelete(clip: Clip) {
        clips.remove(clip.id)
        if (clip.pinned) savePins()
        showClipPanel()
    }

    override fun onClipClearAll() {
        clips.clearUnpinned()
        showClipPanel()
    }

    override fun onClipClose() = hideClipPanel()

    /** Hands over to a voice keyboard the user installed. This app itself never hears anything. */
    private fun startVoice() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        for (im in imm.enabledInputMethodList) {
            if (im.packageName == packageName) continue
            val voice = imm.getEnabledInputMethodSubtypeList(im, true).firstOrNull { it.mode == "voice" } ?: continue
            if (Build.VERSION.SDK_INT >= 28) switchInputMethod(im.id, voice)
            return
        }
        android.widget.Toast.makeText(this, R.string.voice_none, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onEditAction(action: Int) {
        if (action == ClipPanel.VOICE) { startVoice(); return }
        val ic = currentInputConnection ?: return
        fun ctrl(key: Int, meta: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
        }
        when (action) {
            ClipPanel.UNDO -> ctrl(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            ClipPanel.REDO -> ctrl(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            else -> ic.performContextMenuAction(action)
        }
        lastGlide = ""
        lastCorrection = null
        requestSuggestions()
    }

    /** Disk full / IO error: keep the data in memory and try again next time; never crash the keyboard. */
    private fun saveDict() = try { userDict.save() } catch (e: java.io.IOException) {}

    private fun savePins() = try { clips.savePinned(pinnedFile) } catch (e: java.io.IOException) {}

    // Settings and keyboard share one process, so a wipe pressed in Settings lands here at once.
    private val wipeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "wipeLearnedAt" || key == "wipeClipsAt") main.post { applyWipeRequests() }
        if (key == Backup.RELOAD_KEY) worker.post { if (::userDict.isInitialized) userDict.load() }
    }

    /** Settings asks for a wipe by writing a time stamp; we do it on the next keyboard show. */
    private fun applyWipeRequests() {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        if (p.getLong("wipeLearnedAt", 0L) > 0L) {
            worker.post { if (::userDict.isInitialized) userDict.clear() }
            p.edit().remove("wipeLearnedAt").apply()
        }
        if (p.getLong("wipeClipsAt", 0L) > 0L) {
            clips.clearAll()
            savePins()
            p.edit().remove("wipeClipsAt").apply()
        }
    }

    // ---- look ----

    private var theme = KeyTheme.DEFAULT

    /** Theme colours + animation switch to every surface. Never touches sizes. */
    private fun applyLook() {
        theme = KeyTheme.of(this, prefs.base, prefs.accent)
        keys.setTheme(theme)
        val motion = Motion.enabled(prefs.animations)
        keys.animations = motion
        strip.setTheme(theme)
        strip.animations = motion
        emojiPanel?.setTheme(theme)
        root.setBackgroundColor(theme.bg)
    }

    /** Panels rise in over the keys. */
    private fun slideIn(v: View) {
        if (!Motion.enabled(prefs.animations)) return
        v.alpha = 0f
        v.translationY = 24 * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(Motion.NORMAL)
            .setInterpolator(Motion.DECELERATE).start()
    }

    // ---- emoji ----

    private fun showEmoji() {
        hideClipPanel()
        val panel = emojiPanel ?: EmojiPanel(this, keys.height.coerceAtLeast(1)).also {
            it.listener = this
            it.setTheme(theme)
            emojiPanel = it
        }
        panel.keepRecents = !incognito
        panel.open()
        if (panel.parent == null) { root.addView(panel); slideIn(panel) }
        keys.visibility = View.GONE
    }

    private fun hideEmoji() {
        emojiPanel?.let { root.removeView(it) }
        if (::keys.isInitialized && clipPanel?.parent == null) keys.visibility = View.VISIBLE
    }

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        requestSuggestions()
    }

    override fun onEmojiBack() = hideEmoji()

    override fun onEmojiDelete() = onKey(Codes.DELETE)

    // ---- emoji search: the keys type into the search bar instead of the app ----

    private val emojiIndex by lazy {
        val paint = android.graphics.Paint()
        EmojiIndex(assets.open("emoji-names.txt").bufferedReader().use { it.readLines() }.filter { paint.hasGlyph(it.substringBefore('\t')) })
    }

    private fun findEmoji(q: String) = emojiIndex.search(q).map { emojiPanel?.toned(it) ?: it }

    override fun onEmojiSearch() {
        hideEmoji()
        searching = true
        val bar = searchBar ?: EmojiSearchBar(this).also {
            it.listener = this
            searchBar = it
        }
        bar.setTheme(theme)
        bar.show("", emptyList())
        if (bar.parent == null) root.addView(bar, 0)
        strip.visibility = View.GONE
        keys.glideEnabled = false
        keys.swipeEdit = false
        shift = false
        keys.shifted = false
        if (layout?.id !in Layouts.ALL) applyLayout(lang)
    }

    private fun stopEmojiSearch() {
        if (!searching) return
        searching = false
        searchBar?.let { root.removeView(it) }
        strip.visibility = if (noSuggestions && !vaultChip) View.GONE else View.VISIBLE
        keys.swipeEdit = prefs.swipeEdit
        applyLayout(layout?.id ?: lang)
    }

    private fun searchKey(code: Int) {
        val bar = searchBar ?: return
        when (code) {
            Codes.DELETE -> bar.show(bar.query.dropLast(1), findEmoji(bar.query.dropLast(1)))
            Codes.ENTER -> bar.results.firstOrNull()?.let { onSearchPick(it) }
            Codes.SYMBOLS -> applyLayout(if (lang == "ar") "symbols-ar" else "symbols")
            Codes.LETTERS -> applyLayout(lang)
            Codes.EMOJI -> onSearchClose()
            Codes.SHIFT, Codes.LANG, Codes.CLIPBOARD -> Unit
            else -> searchText(String(Character.toChars(code)))
        }
    }

    private fun searchText(text: String) {
        val bar = searchBar ?: return
        val q = (bar.query + text).take(40)
        bar.show(q, findEmoji(q))
    }

    override fun onSearchPick(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        if (!incognito) emojiPanel?.remember(emoji)
    }

    override fun onSearchClose() {
        stopEmojiSearch()
        showEmoji()
    }

    // ---- helpers ----

    private fun applyLayout(id: String) {
        val l = Layouts.get(id, prefs.numberRow)
        layout = l
        val name = (Layouts.ALL[l.id] ?: if (l.id.startsWith("symbols")) Layouts.ALL[lang] else null).orEmpty()
        keys.setLayout(l, prefs.keyHeight, prefs.hints, prefs.vibrate, name)
        keys.glideEnabled = prefs.glide && !noSuggestions && !searching && l.id in Layouts.ALL
        if (l.id in Layouts.ALL) worker.post {
            try { engine(l.id).setLayout(l) } catch (e: java.io.IOException) {} // no suggestions rather than a crash
        }
    }

    private fun updateShift() {
        if (capsLock || !::keys.isInitialized) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        shift = prefs.autoCaps && layout?.rtl == false && layout?.id in Layouts.ALL && ic.getCursorCapsMode(info.inputType) != 0
        keys.shifted = shift
    }

    private fun currentWord(): String =
        trailingWord(currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty())

    private fun trailingWord(before: String): String {
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        return before.substring(i)
    }

    private companion object {
        const val CHIP_MS = 60_000L // a copy this recent is offered in the strip
        val WORD = Regex("[\\p{L}']+")
        val SPACES = Regex("\\s+")
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}

/** How many chars one word-delete removes: trailing spaces, then everything back to the previous space. */
internal fun wordDeleteLength(before: String): Int {
    var i = before.length
    while (i > 0 && before[i - 1].isWhitespace()) i--
    while (i > 0 && !before[i - 1].isWhitespace()) i--
    return before.length - i
}

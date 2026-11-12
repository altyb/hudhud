package dev.altayeb.hudhud

/** Special key codes. Letters use their Unicode code point. */
object Codes {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4   // "123" → symbols layout
    const val LETTERS = -5   // "ABC" → back to letters
    const val LANG = -6      // globe → next language
    const val CLIPBOARD = -7
    const val EMOJI = -8
    const val SPACE = 32
}

/**
 * One key. [hint] = small label, first in the long-press popup ("" = none). [alts] = more popup choices.
 * [width] is relative (1 = normal key).
 */
data class Key(
    val label: String,
    val code: Int,
    val hint: String = "",
    val width: Float = 1f,
    val alts: List<String> = emptyList(),
) {
    /** What long-press offers: hint first, then alternates. */
    val popup: List<String> get() = (if (hint.isEmpty()) alts else listOf(hint) + alts).distinct()
}

/** [id]: "en", "ar", "symbols", "numbers". [rtl] true for Arabic. */
class Layout(val id: String, val rows: List<List<Key>>, val rtl: Boolean)

object Layouts {
    /** Every language with a layout and a dictionary, with its name for the space bar. */
    val ALL = linkedMapOf(
        "en" to "English", "ar" to "العربية", "fr" to "Français", "es" to "Español",
        "de" to "Deutsch", "pt" to "Português", "ru" to "Русский",
    )

    /** The enabled ones from a setting like "en,ar", in that order. Never empty. */
    fun enabled(setting: String): List<String> = setting.split(',').filter { it in ALL }.distinct().ifEmpty { listOf("en") }

    private val cache = HashMap<String, Layout>()

    fun get(id: String, numberRow: Boolean): Layout {
        val real = if (id in ALL || id == "symbols" || id == "symbols-ar" || id == "numbers") id else "en"
        return cache.getOrPut("$real/$numberRow") { build(real, numberRow) }
    }

    private fun build(id: String, numberRow: Boolean): Layout = when (id) {
        "ar" -> arabic(numberRow)
        "symbols" -> symbols()
        "symbols-ar" -> symbols(arabic = true)
        "numbers" -> numbers()
        "fr" -> latin("fr", numberRow, "a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n")
        "es" -> latin("es", numberRow, "q w e r t y u i o p", "a s d f g h j k l ñ", "z x c v b n m")
        "de" -> latin("de", numberRow, "q w e r t z u i o p ü", "a s d f g h j k l ö ä", "y x c v b n m")
        "pt" -> latin("pt", numberRow, "q w e r t y u i o p", "a s d f g h j k l ç", "z x c v b n m")
        "ru" -> latin("ru", numberRow, "й ц у к е н г ш щ з х", "ф ы в а п р о л д ж э", "я ч с м и т ь б ю")
        else -> english(numberRow)
    }

    private val LATIN_PUNCT = listOf("*", "\"", "'", ":", ";", "!", "?")
    private val ARABIC_PUNCT = listOf("*", "\"", "'", ":", "؛", "!", "؟")

    /** Hints for a bottom letter row, counted from its end: "?" is on the key beside delete in every language. */
    private fun punctHints(letters: String, punct: List<String> = LATIN_PUNCT): List<String> {
        val n = letters.split(" ").size
        return if (n >= punct.size) List(n - punct.size) { "" } + punct else punct.takeLast(n)
    }

    /** Accented letters and look-alikes offered on long-press, by base letter. */
    private val ALTS = mapOf(
        "a" to "à á â ä ã å æ", "c" to "ç", "e" to "é è ê ë ē €", "i" to "í ì î ï", "n" to "ñ",
        "е" to "ё", "ь" to "ъ",
        "o" to "ó ò ô ö õ ø œ", "s" to "ß", "u" to "ú ù û ü", "y" to "ý ÿ",
        "ا" to "أ إ آ ٱ", "ي" to "ى ئ", "ى" to "ئ ي", "ه" to "ة", "و" to "ؤ", "ل" to "لا", "ء" to "ئ ؤ",
        "ك" to "گ", "ف" to "ڤ", "ج" to "چ", "ب" to "پ",
    ).mapValues { it.value.split(" ") }

    /** Letters: label = uppercase (or the letter itself for Arabic), code = its code point. */
    private fun letterRow(letters: String, hints: List<String> = emptyList()): List<Key> =
        letters.split(" ").mapIndexed { i, c ->
            val hint = hints.getOrElse(i) { "" }
            Key(c.uppercase(), c[0].code, hint, alts = ALTS[c].orEmpty() + otherQuestionMark(hint))
        }

    /** Top letter row: digits as hints when there is no number row, symbols otherwise (symbol moves to the popup). */
    private fun topRow(letters: String, digits: String, symbols: List<String>, numberRow: Boolean): List<Key> =
        letterRow(letters).mapIndexed { i, k ->
            if (numberRow) k.copy(hint = symbols.getOrElse(i) { "" })
            else k.copy(hint = digits.getOrNull(i)?.toString().orEmpty(), alts = listOfNotNull(symbols.getOrNull(i)?.takeIf { it.isNotEmpty() }) + k.alts)
        }

    /** Bottom row shared by both languages. */
    private fun bottomRow(symbolsLabel: String, comma: String, commaHint: String, punct: List<String>): List<Key> = listOf(
        Key(symbolsLabel, Codes.SYMBOLS, width = 1.5f),
        Key("☺", Codes.EMOJI),
        Key(comma, comma[0].code, commaHint),
        Key("", Codes.SPACE, width = 5f),
        Key(".", '.'.code, punct[0], alts = punct.drop(1)),
        Key("↵", Codes.ENTER, width = 1.5f),
    )

    private fun digitRow(hints: List<String> = emptyList()): List<Key> =
        "1234567890".mapIndexed { i, c -> Key(c.toString(), c.code, hints.getOrElse(i) { "" }) }

    /** Extra symbols on long-press of a symbol key; the first one also shows as the small hint. */
    private val SYM = mapOf(
        "@" to "©", "#" to "~ ^", "$" to "€ £ ¥", "&" to "§", "-" to "– —", "+" to "= ± ×",
        "(" to "[ { <", ")" to "] } >", "/" to "\\ |", "*" to "•", "\"" to "“ ”", "'" to "‘ ’",
        "!" to "¡", "1" to "½", "0" to "°",
    ).mapValues { it.value.split(" ") }

    private fun chars(s: String): List<Key> = s.split(" ").map {
        val a = SYM[it].orEmpty()
        Key(it, it[0].code, hint = a.firstOrNull().orEmpty(), alts = a.drop(1) + otherQuestionMark(it))
    }

    /** Long-press on either question mark also offers the other shape, whatever the language. */
    private fun otherQuestionMark(mark: String) = when (mark) {
        "?" -> listOf("؟")
        "؟" -> listOf("?")
        else -> emptyList()
    }

    private fun english(numberRow: Boolean): Layout {
        val rows = mutableListOf<List<Key>>()
        if (numberRow) rows += digitRow()
        rows += topRow("q w e r t y u i o p", "1234567890", listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}"), numberRow)
        rows += letterRow("a s d f g h j k l", listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"))
        rows += listOf(Key("⇧", Codes.SHIFT, width = 1.5f)) +
            letterRow("z x c v b n m", punctHints("z x c v b n m")) +
            Key("⌫", Codes.DELETE, width = 1.5f)
        rows += bottomRow("123", ",", ";", listOf("?", "؟", "!", ",", ";", ":", "…"))
        return Layout("en", rows, rtl = false)
    }

    /** Any layout that only differs from English in its letters; symbols stay as hints on the digits row. */
    private fun latin(id: String, numberRow: Boolean, top: String, middle: String, bottom: String): Layout {
        val rows = mutableListOf<List<Key>>()
        if (numberRow) rows += digitRow()
        rows += topRow(top, "1234567890", listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}"), numberRow)
        rows += letterRow(middle, listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"))
        rows += listOf(Key("⇧", Codes.SHIFT, width = 1.5f)) +
            letterRow(bottom, punctHints(bottom)) +
            Key("⌫", Codes.DELETE, width = 1.5f)
        rows += bottomRow("123", ",", ";", listOf("?", "؟", "!", ",", ";", ":", "…"))
        return Layout(id, rows, rtl = false)
    }

    private fun arabic(numberRow: Boolean): Layout {
        val rows = mutableListOf<List<Key>>()
        if (numberRow) rows += digitRow(listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"))
        rows += topRow("ض ص ث ق ف غ ع ه خ ح ج", "١٢٣٤٥٦٧٨٩٠", emptyList(), numberRow)
        rows += letterRow("ش س ي ب ل ا ت ن م ك ط", listOf("", "", "ى", "", "لا", "أ"))
        rows += letterRow("ذ ء ؤ ر ى ة و ز ظ د", punctHints("ذ ء ؤ ر ى ة و ز ظ د", ARABIC_PUNCT)) + Key("⌫", Codes.DELETE, width = 1.5f)
        rows += bottomRow("١٢٣", "،", "؛", listOf("؟", "?", "!", "،", "؛", ":", "…"))
        return Layout("ar", rows, rtl = true)
    }

    private fun symbols(arabic: Boolean = false): Layout {
        val comma = if (arabic) "،" else ","
        val rows = listOf(
            digitRow(),
            chars("@ # \$ _ & - + ( ) /"),
            chars(if (arabic) "* % \" ' : ؛ ! ؟" else "* % \" ' : ; ! ?") + Key("⌫", Codes.DELETE, width = 1.5f),
            listOf(
                Key("ABC", Codes.LETTERS, width = 1.5f),
                Key("☺", Codes.EMOJI),
                Key(comma, comma[0].code),
                Key("", Codes.SPACE, width = 5f),
                Key(".", '.'.code),
                Key("↵", Codes.ENTER, width = 1.5f),
            ),
        )
        return Layout(if (arabic) "symbols-ar" else "symbols", rows, rtl = false)
    }

    private fun numbers(): Layout {
        val rows = listOf(
            chars("1 2 3 -"),
            chars("4 5 6 ,"),
            chars("7 8 9") + Key("⌫", Codes.DELETE),
            listOf(
                Key("ABC", Codes.LETTERS),
                Key("0", '0'.code),
                Key(".", '.'.code),
                Key("%", '%'.code),
                Key("↵", Codes.ENTER),
            ),
        )
        return Layout("numbers", rows, rtl = false)
    }
}

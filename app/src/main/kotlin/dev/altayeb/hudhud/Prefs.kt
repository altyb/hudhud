package dev.altayeb.hudhud

import android.content.Context

/** Every user switch, read once per keyboard show. Keys are the SharedPreferences names. */
data class Prefs(
    val suggestions: Boolean = true,
    val predictNext: Boolean = true,
    val autocorrect: Boolean = true,
    val learn: Boolean = true,
    val contacts: Boolean = false,
    val clipboard: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val vibrate: Boolean = true,
    val sound: Boolean = false,
    val glide: Boolean = true,
    val swipeEdit: Boolean = true,        // hold + drag on space = cursor, slide on delete = words
    val oneHanded: Int = 0,               // -1 left, 0 off, +1 right
    val languages: String = "en,ar",      // comma list, order = switching order
    val split: Boolean = false,           // gap in the middle, on screens 600dp and wider only
    val numberRow: Boolean = false,
    val hints: Boolean = true,
    val keyHeight: Int = 100, // percent, 80..140
    // Privacy
    val incognito: Boolean = false,       // no learning, no clipboard history, anywhere
    val clearClipsOnLock: Boolean = true, // wipe clipboard history when the screen turns off
    val secureKeyboard: Boolean = true,   // block screenshots/recording of the keys in password fields
    val vaultChip: Boolean = true,        // Passwords button above the keys in login fields
    // Look (colours and motion only, never sizes)
    val base: String = "charcoal",        // a KeyTheme.BASES id: the surface colours
    val animations: Boolean = true,
    val accent: Int = KeyTheme.ORANGE,    // 0 = wallpaper colour (Android 12+)
) {
    companion object {
        const val FILE = "prefs"

        fun read(context: Context): Prefs {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val d = Prefs()
            return Prefs(
                suggestions = p.getBoolean("suggestions", d.suggestions),
                predictNext = p.getBoolean("predictNext", d.predictNext),
                autocorrect = p.getBoolean("autocorrect", d.autocorrect),
                learn = p.getBoolean("learn", d.learn),
                contacts = p.getBoolean("contacts", d.contacts),
                clipboard = p.getBoolean("clipboard", d.clipboard),
                autoCaps = p.getBoolean("autoCaps", d.autoCaps),
                doubleSpacePeriod = p.getBoolean("doubleSpacePeriod", d.doubleSpacePeriod),
                vibrate = p.getBoolean("vibrate", d.vibrate),
                sound = p.getBoolean("sound", d.sound),
                glide = p.getBoolean("glide", d.glide),
                swipeEdit = p.getBoolean("swipeEdit", d.swipeEdit),
                oneHanded = p.getInt("oneHanded", d.oneHanded).coerceIn(-1, 1),
                languages = p.getString("languages", d.languages) ?: d.languages,
                split = p.getBoolean("split", d.split),
                numberRow = p.getBoolean("numberRow", d.numberRow),
                hints = p.getBoolean("hints", d.hints),
                keyHeight = p.getInt("keyHeight", d.keyHeight).coerceIn(80, 140),
                incognito = p.getBoolean("incognito", d.incognito),
                clearClipsOnLock = p.getBoolean("clearClipsOnLock", d.clearClipsOnLock),
                secureKeyboard = p.getBoolean("secureKeyboard", d.secureKeyboard),
                vaultChip = p.getBoolean("vaultChip", d.vaultChip),
                base = p.getString("base", d.base) ?: d.base,
                animations = p.getBoolean("animations", d.animations),
                accent = p.getInt("accent", d.accent),
            )
        }
    }
}

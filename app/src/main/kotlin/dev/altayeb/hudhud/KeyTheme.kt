package dev.altayeb.hudhud

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Colours for every keyboard surface. Sizes never depend on the theme. */
data class KeyTheme(
    val bg: Int,          // behind the keys
    val key: Int,
    val keyEdge: Int,     // unused while [edge] is false
    val pressed: Int,
    val label: Int,
    val accent: Int,      // icons, special labels, shift on, selection
    val onAccent: Int,    // text drawn on accent
    val hint: Int,
    val panel: Int,       // strip, panel headers, popups
    val card: Int,        // clipboard cards
    val textDim: Int,
    val special: Int = key,     // shift / delete / enter / 123 / emoji key colour
    val specialLabel: Int = accent,
    val cornerDp: Float = 6f,   // key corner radius: near-rectangular
    val edge: Boolean = false,  // darker bottom strip
    val pressScale: Float = 0.9f, // < 1 = key shrinks while held and springs back
) {
    /** A surface colour set. The accent is chosen separately, so any base goes with any accent. */
    class Base(val bg: Int, val key: Int, val special: Int, val pressed: Int, val panel: Int, val card: Int,
               val label: Int = 0xFFE6E0E9.toInt(), val hint: Int = 0xFFB4B2B8.toInt())

    companion object {
        private fun b(bg: Long, key: Long, special: Long, pressed: Long, panel: Long, card: Long) =
            Base(bg.toInt(), key.toInt(), special.toInt(), pressed.toInt(), panel.toInt(), card.toInt())

        /** id → surfaces, in the order the settings show them. */
        val BASES = linkedMapOf(
            "charcoal" to b(0xFF0C0C0D, 0xFF2A2A2D, 0xFF39393D, 0xFF414146, 0xFF161618, 0xFF212124),
            "black" to Base(0xFF000000.toInt(), 0xFF1A1A1A.toInt(), 0xFF262626.toInt(), 0xFF3A3A3A.toInt(), 0xFF0A0A0A.toInt(), 0xFF141414.toInt(), 0xFFF2F2F2.toInt(), 0xFF9A9A9A.toInt()),
            "graphite" to b(0xFF1C1C1E, 0xFF3A3A3C, 0xFF48484A, 0xFF5A5A5E, 0xFF242426, 0xFF2C2C2E),
            "slate" to b(0xFF0F1418, 0xFF27323A, 0xFF33414B, 0xFF435561, 0xFF161D23, 0xFF1F2830),
            "midnight" to b(0xFF0A1022, 0xFF1E2745, 0xFF2A3560, 0xFF3B4A80, 0xFF111832, 0xFF18213F),
            "forest" to b(0xFF0B140F, 0xFF213427, 0xFF2D4535, 0xFF3F5E49, 0xFF111C15, 0xFF192A1F),
            "wine" to b(0xFF150A0E, 0xFF3A1F28, 0xFF4E2B38, 0xFF693B4C, 0xFF1E0F15, 0xFF2B1720),
            "mocha" to b(0xFF14100D, 0xFF372D26, 0xFF4A3D34, 0xFF62524A, 0xFF1D1713, 0xFF2A211B),
            "teal" to b(0xFF0B1F25, 0xFF1F4350, 0xFF2B5663, 0xFF3A6E7E, 0xFF12292F, 0xFF1A3740),
            "paper" to Base(0xFFD9D6CB.toInt(), 0xFFFFFFFF.toInt(), 0xFFC4C0B2.toInt(), 0xFFB0AC9E.toInt(), 0xFFEDEADF.toInt(), 0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFF6B6B66.toInt()),
        )

        /** 0 = wallpaper colour on Android 12+ (hoopoe orange before that). */
        val ACCENTS = listOf(
            0, 0xFFF2A65A.toInt(), 0xFF71E2FB.toInt(), 0xFF7BD88F.toInt(), 0xFFFF7A90.toInt(), 0xFFC3A6FF.toInt(),
            0xFFFFD54F.toInt(), 0xFFFF6B5B.toInt(), 0xFF82B1FF.toInt(), 0xFF4DD0C4.toInt(), 0xFFE6E0E9.toInt(),
        )
        const val ORANGE = 0xFFF2A65A.toInt()

        val DEFAULT = build(BASES.getValue("charcoal"), ORANGE)

        fun of(context: Context, base: String, accent: Int): KeyTheme {
            val a = if (accent != 0) accent
                else if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_200) else ORANGE
            return build(BASES[base] ?: BASES.getValue("charcoal"), a)
        }

        private fun build(s: Base, accent: Int) = KeyTheme(
            bg = s.bg, key = s.key, keyEdge = s.key, pressed = s.pressed, label = s.label,
            accent = accent, onAccent = if (Color.luminance(accent) > 0.4f) Color.BLACK else Color.WHITE,
            hint = s.hint, panel = s.panel, card = s.card, textDim = s.hint, special = s.special, specialLabel = accent,
        )
    }
}

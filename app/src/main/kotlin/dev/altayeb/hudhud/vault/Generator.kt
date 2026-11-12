package dev.altayeb.hudhud.vault

import java.security.SecureRandom
import kotlin.math.log2

data class GenOptions(
    val length: Int = 20, val lower: Boolean = true, val upper: Boolean = true,
    val digits: Boolean = true, val symbols: Boolean = true, val noLookalikes: Boolean = false,
)

object Generator {
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private const val LOOKALIKE = "O0oIl1|"

    fun password(o: GenOptions = GenOptions(), r: SecureRandom = SecureRandom()): String {
        val classes = listOfNotNull(
            LOWER.takeIf { o.lower }, UPPER.takeIf { o.upper }, DIGITS.takeIf { o.digits }, SYMBOLS.takeIf { o.symbols },
        ).map { c -> if (o.noLookalikes) c.filterNot { it in LOOKALIKE } else c }
        require(classes.isNotEmpty()) { "no character class" }
        require(o.length in classes.size..256) { "bad length" }
        val all = classes.joinToString("")
        val out = CharArray(o.length) { i -> if (i < classes.size) classes[i][r.nextInt(classes[i].length)] else all[r.nextInt(all.length)] }
        for (i in out.size - 1 downTo 1) { // nextInt is unbiased; shuffle so the guaranteed classes are not always first
            val j = r.nextInt(i + 1)
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return String(out)
    }

    fun passphrase(words: List<String>, count: Int = 6, sep: String = "-", r: SecureRandom = SecureRandom()): String {
        require(count >= 1 && words.isNotEmpty()) { "bad passphrase settings" }
        return (1..count).joinToString(sep) { words[r.nextInt(words.size)] }
    }

    fun entropyBits(password: String): Double {
        if (password.isEmpty()) return 0.0
        var size = 0
        if (password.any { it in LOWER }) size += 26
        if (password.any { it in UPPER }) size += 26
        if (password.any { it in DIGITS }) size += 10
        if (password.any { it in SYMBOLS }) size += SYMBOLS.length
        if (password.any { it !in LOWER && it !in UPPER && it !in DIGITS && it !in SYMBOLS }) size += 100
        return password.length * log2(size.toDouble())
    }
}

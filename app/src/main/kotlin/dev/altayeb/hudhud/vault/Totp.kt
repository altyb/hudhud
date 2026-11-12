package dev.altayeb.hudhud.vault

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpConfig(val secret: ByteArray, val digits: Int = 6, val period: Int = 30, val algorithm: String = "SHA1")

object Totp {
    fun parse(text: String): TotpConfig? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("otpauth://", ignoreCase = true)) return base32(t)?.let { TotpConfig(it) }
        val uri = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        if (!uri.host.equals("totp", true)) return null
        val q = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val secret = q["secret"]?.let(::base32) ?: return null
        val digits = q["digits"]?.let { it.toIntOrNull() ?: return null } ?: 6
        val period = q["period"]?.let { it.toIntOrNull() ?: return null } ?: 30
        val algo = (q["algorithm"] ?: "SHA1").uppercase()
        if (digits !in 6..8 || period !in 1..300 || algo !in setOf("SHA1", "SHA256", "SHA512")) return null
        return TotpConfig(secret, digits, period, algo)
    }

    fun code(c: TotpConfig, epochSeconds: Long): String {
        val counter = Math.floorDiv(epochSeconds, c.period.toLong())
        val m = Mac.getInstance("Hmac" + c.algorithm)
        m.init(SecretKeySpec(c.secret, "Hmac" + c.algorithm))
        val h = m.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
        val o = h.last().toInt() and 0xF
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1L
        repeat(c.digits) { mod *= 10 }
        return (bin % mod).toString().padStart(c.digits, '0')
    }

    fun secondsLeft(c: TotpConfig, epochSeconds: Long): Int =
        c.period - Math.floorMod(epochSeconds, c.period.toLong()).toInt()

    private fun base32(s: String): ByteArray? {
        val clean = s.filterNot { it == ' ' || it == '-' }.trimEnd('=').uppercase()
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var buf = 0
        for (c in clean) {
            val v = when (c) { in 'A'..'Z' -> c - 'A'; in '2'..'7' -> c - '2' + 26; else -> return null }
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) { bits -= 8; out.write((buf ushr bits) and 0xFF); buf = buf and ((1 shl bits) - 1) }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}

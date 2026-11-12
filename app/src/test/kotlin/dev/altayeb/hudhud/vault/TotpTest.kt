package dev.altayeb.hudhud.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpTest {

    // RFC 6238 Appendix B seeds
    private val seed1 = "12345678901234567890".toByteArray()
    private val seed256 = "12345678901234567890123456789012".toByteArray()
    private val seed512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    private fun cfg(seed: ByteArray, alg: String, period: Int = 30) =
        TotpConfig(seed, digits = 8, period = period, algorithm = alg)

    @Test fun rfc6238Sha1EightDigits() {
        val c = cfg(seed1, "SHA1")
        val cases = listOf(
            59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130",
        )
        for ((t, code) in cases) assertEquals("t=$t", code, Totp.code(c, t))
    }

    @Test fun rfc6238Sha256EightDigits() {
        val c = cfg(seed256, "SHA256")
        val cases = listOf(
            59L to "46119246", 1111111109L to "68084774", 1111111111L to "67062674",
            1234567890L to "91819424", 2000000000L to "90698825", 20000000000L to "77737706",
        )
        for ((t, code) in cases) assertEquals("t=$t", code, Totp.code(c, t))
    }

    @Test fun rfc6238Sha512EightDigits() {
        val c = cfg(seed512, "SHA512")
        val cases = listOf(
            59L to "90693936", 1111111109L to "25091201", 1111111111L to "99943326",
            1234567890L to "93441116", 2000000000L to "38618901", 20000000000L to "47863826",
        )
        for ((t, code) in cases) assertEquals("t=$t", code, Totp.code(c, t))
    }

    @Test fun defaultSixDigitsIsLastSixOfRfcValue() {
        // defaults: 6 digits, 30 s, SHA1. 94287082 -> 287082; 1111111109 -> 081804 (zero-padded)
        val c = TotpConfig(seed1)
        assertEquals(6, c.digits)
        assertEquals(30, c.period)
        assertEquals("SHA1", c.algorithm)
        assertEquals("287082", Totp.code(c, 59L))
        assertEquals("081804", Totp.code(c, 1111111109L))
    }

    @Test fun sameThirtySecondWindowGivesSameCode() {
        val c = cfg(seed1, "SHA1")
        assertEquals(Totp.code(c, 60L), Totp.code(c, 89L))
        assertEquals(Totp.code(c, 30L), Totp.code(c, 59L))
    }

    @Test fun periodChangesCounter() {
        // period 60 at t=59 is counter 0, same as period 30 at t=29
        assertEquals(
            Totp.code(cfg(seed1, "SHA1", 30), 29L),
            Totp.code(cfg(seed1, "SHA1", 60), 59L),
        )
    }

    @Test fun parsesOtpauthUriWithDefaults() {
        val c = Totp.parse("otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example")
        assertNotNull(c)
        val hello = byteArrayOf(72, 101, 108, 108, 111, 33, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertArrayEquals(hello, c!!.secret)
        assertEquals(6, c.digits)
        assertEquals(30, c.period)
        assertEquals("SHA1", c.algorithm)
    }

    @Test fun parsesOtpauthUriParameters() {
        val c = Totp.parse("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&period=60&algorithm=SHA256")
        assertNotNull(c)
        assertEquals(8, c!!.digits)
        assertEquals(60, c.period)
        assertEquals("SHA256", c.algorithm)
    }

    @Test fun parsedRfcSeedReproducesRfcCode() {
        val c = Totp.parse("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8")
        assertNotNull(c)
        assertEquals("94287082", Totp.code(c!!, 59L))
    }

    @Test fun bareBase32IsAcceptedWithSpacesLowercaseAndNoPadding() {
        val want = Totp.parse("JBSWY3DPEHPK3PXP")!!.secret
        assertArrayEquals(want, Totp.parse("jbswy3dpehpk3pxp")!!.secret)
        assertArrayEquals(want, Totp.parse("JBSW Y3DP EHPK 3PXP")!!.secret)
        assertArrayEquals(want, Totp.parse("  jbsw y3dp ehpk 3pxp  ")!!.secret)
        // "MZXW6" is "foo" without padding; "MZXW6===" is the padded form
        assertArrayEquals("foo".toByteArray(), Totp.parse("MZXW6")!!.secret)
        assertArrayEquals("foo".toByteArray(), Totp.parse("MZXW6===")!!.secret)
    }

    @Test fun bareBase32UsesDefaults() {
        val c = Totp.parse("JBSWY3DPEHPK3PXP")!!
        assertEquals(6, c.digits)
        assertEquals(30, c.period)
        assertEquals("SHA1", c.algorithm)
    }

    @Test fun badInputIsNull() {
        assertNull(Totp.parse(""))
        assertNull(Totp.parse("   "))
        assertNull(Totp.parse("not base32!!"))
        assertNull(Totp.parse("JBSWY3DP1")) // '1' is not in base32
        assertNull(Totp.parse("otpauth://totp/x"))                       // no secret
        assertNull(Totp.parse("otpauth://totp/x?secret="))               // empty secret
    }

    @Test fun hotpIsNull() {
        assertNull(Totp.parse("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP&counter=1"))
    }

    @Test fun digitsOutsideSixToEightAreRefused() {
        assertNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=5"))
        assertNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=9"))
        assertNotNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=6"))
        assertNotNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=8"))
    }

    @Test fun periodOutsideOneToThreeHundredIsRefused() {
        assertNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0"))
        assertNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=301"))
        assertNotNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=1"))
        assertNotNull(Totp.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=300"))
    }

    @Test fun secondsLeftCountsDownInsideThePeriod() {
        val c = TotpConfig(seed1, period = 30)
        assertEquals(1, Totp.secondsLeft(c, 59L))   // 59 % 30 = 29
        assertEquals(1, Totp.secondsLeft(c, 29L))
        assertEquals(29, Totp.secondsLeft(c, 31L))
        // ambiguity: at the exact window start the spec does not say 30 or 0; this asserts 30
        assertEquals(30, Totp.secondsLeft(c, 60L))
        assertEquals(30, Totp.secondsLeft(c, 0L))
        val c60 = TotpConfig(seed1, period = 60)
        assertEquals(1, Totp.secondsLeft(c60, 59L))
    }

    @Test fun secondsLeftStaysWithinOnePeriod() {
        val c = TotpConfig(seed1, period = 30)
        for (t in 0L..120L) {
            val s = Totp.secondsLeft(c, t)
            assertTrue("t=$t left=$s", s in 1..30)
        }
    }
}

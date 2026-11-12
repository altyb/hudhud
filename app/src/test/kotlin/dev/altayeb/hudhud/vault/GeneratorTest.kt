package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class GeneratorTest {

    private val rnd = SecureRandom()
    private val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel")

    private fun isLower(c: Char) = c in 'a'..'z'
    private fun isUpper(c: Char) = c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isSymbol(c: Char) = !isLower(c) && !isUpper(c) && !isDigit(c)

    private fun log2(x: Double) = Math.log(x) / Math.log(2.0)

    // ---- password ----

    @Test fun defaultLengthIsTwenty() {
        assertEquals(20, Generator.password(GenOptions(), rnd).length)
    }

    @Test fun everyEnabledClassIsPresentInEveryPassword() {
        repeat(300) {
            val p = Generator.password(GenOptions(), rnd)
            assertTrue("no lower in $p", p.any(::isLower))
            assertTrue("no upper in $p", p.any(::isUpper))
            assertTrue("no digit in $p", p.any(::isDigit))
            assertTrue("no symbol in $p", p.any(::isSymbol))
        }
    }

    @Test fun lengthEqualToClassCountStillHasEveryClass() {
        repeat(300) {
            val p = Generator.password(GenOptions(length = 4), rnd)
            assertEquals(4, p.length)
            assertTrue(p.any(::isLower) && p.any(::isUpper) && p.any(::isDigit) && p.any(::isSymbol))
        }
    }

    @Test fun onlyDigitsEnabledGivesOnlyDigits() {
        val o = GenOptions(length = 12, lower = false, upper = false, digits = true, symbols = false)
        repeat(100) {
            val p = Generator.password(o, rnd)
            assertEquals(12, p.length)
            assertTrue(p, p.all(::isDigit))
        }
    }

    @Test fun disabledClassesNeverAppear() {
        val o = GenOptions(length = 30, upper = false, symbols = false)
        repeat(300) {
            val p = Generator.password(o, rnd)
            assertTrue(p, p.none(::isUpper))
            assertTrue(p, p.none(::isSymbol))
            assertTrue(p, p.any(::isLower) && p.any(::isDigit))
        }
    }

    @Test fun noLookalikesRemovesOOoIl1Pipe() {
        val o = GenOptions(length = 64, noLookalikes = true)
        repeat(300) {
            val p = Generator.password(o, rnd)
            assertTrue(p, p.none { it in "O0oIl1|" })
        }
    }

    @Test fun noLookalikesStillKeepsEachClass() {
        val o = GenOptions(length = 8, noLookalikes = true)
        repeat(100) {
            val p = Generator.password(o, rnd)
            assertTrue(p, p.any(::isLower) && p.any(::isUpper) && p.any(::isDigit) && p.any(::isSymbol))
        }
    }

    @Test fun noClassEnabledThrows() {
        val o = GenOptions(lower = false, upper = false, digits = false, symbols = false)
        assertThrows(IllegalArgumentException::class.java) { Generator.password(o, rnd) }
    }

    @Test fun lengthBelowEnabledClassCountThrows() {
        assertThrows(IllegalArgumentException::class.java) { Generator.password(GenOptions(length = 3), rnd) }
        assertThrows(IllegalArgumentException::class.java) { Generator.password(GenOptions(length = 0), rnd) }
        assertThrows(IllegalArgumentException::class.java) {
            Generator.password(GenOptions(length = 1, lower = true, upper = true, digits = false, symbols = false), rnd)
        }
    }

    @Test fun lengthEqualToEnabledClassCountIsAllowed() {
        assertEquals(2, Generator.password(GenOptions(length = 2, digits = false, symbols = false), rnd).length)
    }

    @Test fun lengthLimitIsTwoFiftySix() {
        assertEquals(256, Generator.password(GenOptions(length = 256), rnd).length)
        assertThrows(IllegalArgumentException::class.java) { Generator.password(GenOptions(length = 257), rnd) }
    }

    @Test fun noModuloBiasOverDigits() {
        // 256 % 10 = 6, so a plain modulo would make digits 0..5 about 0.4% more likely.
        // Over 600k draws that is about 6 standard deviations for digit 9, so a 5-sigma band catches it.
        val o = GenOptions(length = 1, lower = false, upper = false, digits = true, symbols = false)
        val n = 600_000
        val counts = IntArray(10)
        repeat(n) { counts[Generator.password(o, rnd)[0] - '0']++ }
        val expected = n / 10.0
        val sigma = Math.sqrt(n * 0.1 * 0.9)
        for (d in 0..9) {
            assertTrue("digit $d count ${counts[d]}", Math.abs(counts[d] - expected) < 5 * sigma)
        }
    }

    // ---- passphrase ----

    @Test fun passphraseDefaultsToSixWordsWithDash() {
        val p = Generator.passphrase(words, r = rnd)
        val parts = p.split("-")
        assertEquals(6, parts.size)
        assertTrue(parts.all { it in words })
    }

    @Test fun passphraseHonoursCountAndSeparator() {
        val parts = Generator.passphrase(words, count = 3, sep = " ", r = rnd).split(" ")
        assertEquals(3, parts.size)
        assertTrue(parts.all { it in words })
    }

    @Test fun passphraseOfOneWordHasNoSeparator() {
        val p = Generator.passphrase(words, count = 1, r = rnd)
        assertTrue(p in words)
    }

    @Test fun passphraseRefusesBadCountOrEmptyList() {
        assertThrows(IllegalArgumentException::class.java) { Generator.passphrase(words, count = 0, r = rnd) }
        assertThrows(IllegalArgumentException::class.java) { Generator.passphrase(words, count = -1, r = rnd) }
        assertThrows(IllegalArgumentException::class.java) { Generator.passphrase(emptyList(), r = rnd) }
    }

    // ---- entropyBits ----

    @Test fun emptyHasZeroEntropy() {
        assertEquals(0.0, Generator.entropyBits(""), 1e-9)
    }

    @Test fun lowerOnlyUsesTwentySixSymbols() {
        assertEquals(10 * log2(26.0), Generator.entropyBits("abcdefghij"), 1e-9)
    }

    @Test fun lowerAndDigitsUseThirtySixSymbols() {
        assertEquals(6 * log2(36.0), Generator.entropyBits("abc123"), 1e-9)
    }

    @Test fun lowerAndUpperUseFiftyTwoSymbols() {
        assertEquals(3 * log2(52.0), Generator.entropyBits("Abc"), 1e-9)
    }

    @Test fun digitsOnly() {
        assertEquals(3 * log2(10.0), Generator.entropyBits("123"), 1e-9)
    }

    @Test fun addingASymbolClassRaisesEntropy() {
        // symbol class size is not fixed by the spec, so only the direction is asserted
        assertTrue(Generator.entropyBits("a!") > Generator.entropyBits("aa"))
    }
}

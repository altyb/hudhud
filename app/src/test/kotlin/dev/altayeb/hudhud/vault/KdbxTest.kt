package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

class KdbxTest {

    // Fresh arrays every time: the implementation may wipe the password it is given.
    private fun pw() = "correct horse".toCharArray()
    private val cheap = KdfParams(memoryBytes = 1L shl 20, iterations = 1, parallelism = 1)

    private fun vault(vararg entries: VaultEntry) = Vault(name = "Hudhud", entries = entries.toList())

    private fun rich() = VaultEntry(
        uuid = UUID.fromString("0f1e2d3c-4b5a-4968-8778-6a5b4c3d2e1f"),
        title = "بنك 🔑 <>&\"'",
        username = "أحمد@mail.com",
        password = "كلمة-سر🐦<>&\"'\\",
        url = "https://example.com/login?a=1&b=<2>",
        notes = "ملاحظة 📝\nline2 \"quoted\" 'single' & more",
        totp = "otpauth://totp/Example:me?secret=JBSWY3DPEHPK3PXP&issuer=Example",
        group = "Work/Mail",
        apps = listOf(AppLink("com.example.app", "0123456789abcdef".repeat(4))),
        fields = mapOf("PIN" to "1234", "Recovery" to "x<y>&z", "Arabic" to "مرحبا 🌙"),
        modified = 1_700_000_000L,
        passwordChanged = 1_650_000_000L,
    )

    private fun assertRejected(bytes: ByteArray, label: String) {
        try {
            Kdbx.read(bytes, pw())
            fail("tampered file accepted: $label")
        } catch (e: BadPasswordException) {
        } catch (e: CorruptVaultException) {
        }
    }

    @Test fun everyFieldRoundTrips() {
        val e = rich()
        val back = Kdbx.read(Kdbx.write(vault(e), pw(), cheap), pw())
        assertEquals("Hudhud", back.name)
        assertEquals(listOf(e), back.entries)
    }

    @Test fun emptyEntryAndRootGroupRoundTrip() {
        val e = VaultEntry(title = "t")
        val back = Kdbx.read(Kdbx.write(vault(e), pw(), cheap), pw())
        assertEquals(listOf(e), back.entries)
        assertEquals("", back.entries[0].group)
    }

    @Test fun entryOrderAndCountSurvive() {
        val es = (1..5).map { VaultEntry(title = "e$it", password = "p$it") }
        val back = Kdbx.read(Kdbx.write(vault(*es.toTypedArray()), pw(), cheap), pw())
        assertEquals(es, back.entries)
    }

    @Test fun customFieldsAppLinksAndNestedGroupSurvive() {
        val e = VaultEntry(
            title = "t", password = "p", group = "Work/Mail/Deep",
            apps = listOf(AppLink("com.a", "aa".repeat(32)), AppLink("com.b", "bb".repeat(32))),
            fields = mapOf("HudhudX" to "v", "Other" to "a,b:c", "Empty" to ""),
        )
        val back = Kdbx.read(Kdbx.write(vault(e), pw(), cheap), pw()).entries[0]
        assertEquals(e.apps, back.apps)
        assertEquals(e.fields, back.fields)
        assertEquals("Work/Mail/Deep", back.group)
    }

    @Test fun wrongPasswordIsBadPassword() {
        val bytes = Kdbx.write(vault(rich()), pw(), cheap)
        assertThrows(BadPasswordException::class.java) { Kdbx.read(bytes, "wrong".toCharArray()) }
    }

    @Test fun flippedByteAtManyOffsetsIsRejected() {
        val good = Kdbx.write(vault(rich()), pw(), cheap)
        val n = good.size
        // header area, then a sweep over the whole file (header HMAC, every block), then the last byte
        val offsets = (listOf(0, 4, 8, 12, 20, 40, 80, 120, 160, 200, 240, 280) + (0 until n step maxOf(1, n / 40)))
            .filter { it < n }.toSet() + (n - 1)
        for (i in offsets) {
            val bad = good.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertRejected(bad, "offset $i of $n")
        }
    }

    @Test fun truncationIsRejected() {
        val good = Kdbx.write(vault(rich()), pw(), cheap)
        for (len in listOf(0, 1, 12, 100, good.size / 2, good.size - 1)) {
            assertRejected(good.copyOf(len), "length $len")
        }
    }

    @Test fun garbageAndEmptyInputAreRejected() {
        assertRejected(ByteArray(0), "empty")
        assertRejected(ByteArray(1), "one zero byte")
        assertRejected("not a kdbx file at all".toByteArray(), "text")
        val random = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        assertRejected(random, "random 4 KiB")
    }

    @Test fun kdbx3SignatureIsCorrupt() {
        // KDBX 3.1 signature (03 D9 A2 9A, 67 FB 4B B5), version minor 0 major 3, then junk
        val kdbx3 = byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(),
            0x00, 0x00, 0x03, 0x00,
        ) + ByteArray(256)
        assertThrows(CorruptVaultException::class.java) { Kdbx.read(kdbx3, pw()) }
    }

    @Test fun twoWritesOfTheSameVaultDiffer() {
        val v = vault(rich())
        val a = Kdbx.write(v, pw(), cheap)
        val b = Kdbx.write(v, pw(), cheap)
        assertFalse(a.contentEquals(b))
        assertEquals(v.entries, Kdbx.read(a, pw()).entries)
        assertEquals(v.entries, Kdbx.read(b, pw()).entries)
    }

    @Test fun iterationsAboveLimitAreCorrupt() {
        val hostile = KdfParams(memoryBytes = 1L shl 20, iterations = 101, parallelism = 1)
        val bytes = Kdbx.write(vault(rich()), pw(), hostile)
        assertThrows(CorruptVaultException::class.java) { Kdbx.read(bytes, pw()) }
    }

    @Test fun parallelismAboveLimitIsCorrupt() {
        val hostile = KdfParams(memoryBytes = 1L shl 20, iterations = 1, parallelism = 17)
        val bytes = Kdbx.write(vault(rich()), pw(), hostile)
        assertThrows(CorruptVaultException::class.java) { Kdbx.read(bytes, pw()) }
    }
}

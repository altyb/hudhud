package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Other half of tools/kdbx_interop.py: real files exchanged with an independent KeePass library. Runs only with HUDHUD_INTEROP_DIR set. */
class InteropTest {
    private val dir = System.getenv("HUDHUD_INTEROP_DIR")?.let(::File)
    private val pw = "pässword-كلمة-🔑".toCharArray()

    @Test fun writeForOracle() {
        assumeTrue(dir != null)
        val e = VaultEntry(title = "مصرف <&>\"'", username = "user@example.com", password = "s3cret-🔑-كلمة", url = "https://example.com/login",
            notes = "line1\nline2", totp = "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP", group = "Work/Mail", modified = 1_700_000_000,
            fields = mapOf("Custom" to "value"))
        File(dir, "ours.kdbx").writeBytes(Kdbx.write(Vault("Interop", listOf(e, VaultEntry(title = "root", password = "x"))), pw))
    }

    @Test fun readFromOracle() {
        assumeTrue(dir != null && File(dir, "theirs.kdbx").exists())
        val v = Kdbx.read(File(dir, "theirs.kdbx").readBytes(), pw)
        val e = v.entries.single { it.title == "من البايثون" }
        assertEquals("py-user", e.username)
        assertEquals("py-pass-🔑", e.password)
        assertEquals("https://py.example/", e.url)
        assertEquals("Team/Sub", e.group)
        assertEquals("custom-value", e.fields["PyCustom"])
    }
}

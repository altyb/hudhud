package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditTest {

    private val now = 1_800_000_000L
    private val day = 86_400L
    private val strong = "Xk9#mQ2@vL7!pR4&tN8z" // 20 chars, four classes: far above 50 bits

    private fun e(pw: String, changed: Long = 0L, title: String = "t") =
        VaultEntry(title = title, password = pw, passwordChanged = changed)

    private fun issuesOf(findings: List<Finding>, entry: VaultEntry): Set<Issue> =
        findings.firstOrNull { it.entry.uuid == entry.uuid }?.issues ?: emptySet()

    @Test fun weakBelowFiftyBits() {
        val weak = e("abcdefghij") // 10 lower-case: about 47.0 bits
        val issues = issuesOf(Audit.run(listOf(weak), now), weak)
        assertTrue(issues.contains(Issue.WEAK))
    }

    @Test fun notWeakAtFiftyBitsOrMore() {
        val ok = e("abcdefghijk") // 11 lower-case: about 51.7 bits
        assertFalse(issuesOf(Audit.run(listOf(ok), now), ok).contains(Issue.WEAK))
    }

    @Test fun emptyPasswordIsNoPassword() {
        val empty = e("")
        assertTrue(issuesOf(Audit.run(listOf(empty), now), empty).contains(Issue.NO_PASSWORD))
    }

    @Test fun emptyPasswordsAreNotReusedWithEachOther() {
        val a = e("")
        val b = e("")
        val f = Audit.run(listOf(a, b), now)
        assertFalse(issuesOf(f, a).contains(Issue.REUSED))
        assertFalse(issuesOf(f, b).contains(Issue.REUSED))
    }

    @Test fun sharedPasswordIsReusedOnEveryHolder() {
        val a = e(strong, title = "a")
        val b = e(strong, title = "b")
        val c = e(strong, title = "c")
        val f = Audit.run(listOf(a, b, c), now)
        assertEquals(setOf(Issue.REUSED), issuesOf(f, a))
        assertEquals(setOf(Issue.REUSED), issuesOf(f, b))
        assertEquals(setOf(Issue.REUSED), issuesOf(f, c))
    }

    @Test fun reuseIsExactMatchCaseSensitive() {
        val a = e("Pass1")
        val b = e("pass1")
        val f = Audit.run(listOf(a, b), now)
        assertFalse(issuesOf(f, a).contains(Issue.REUSED))
        assertFalse(issuesOf(f, b).contains(Issue.REUSED))
    }

    @Test fun oldAtExactlyThreeHundredSixtyFiveDaysIsNotOld() {
        val a = e(strong, changed = now - 365 * day)
        assertTrue(Audit.run(listOf(a), now).isEmpty())
    }

    @Test fun oldOverThreeHundredSixtyFiveDaysIsOld() {
        val a = e(strong, changed = now - 365 * day - 1)
        assertEquals(setOf(Issue.OLD), issuesOf(Audit.run(listOf(a), now), a))
    }

    @Test fun zeroPasswordChangedIsNeverOld() {
        val a = e(strong, changed = 0L)
        assertTrue(Audit.run(listOf(a), now).isEmpty())
    }

    @Test fun oneEntryCanHaveSeveralIssues() {
        val a = e("abc", changed = now - 400 * day)
        assertEquals(setOf(Issue.WEAK, Issue.OLD), issuesOf(Audit.run(listOf(a), now), a))
    }

    @Test fun cleanEntriesAreLeftOut() {
        val good = e(strong, changed = now - day, title = "good")
        val weak = e("abc", title = "weak")
        val f = Audit.run(listOf(good, weak), now)
        assertEquals(1, f.size)
        assertEquals(weak.uuid, f[0].entry.uuid)
        assertEquals(weak, f[0].entry)
    }

    @Test fun noEntriesGiveNoFindings() {
        assertTrue(Audit.run(emptyList(), now).isEmpty())
    }
}

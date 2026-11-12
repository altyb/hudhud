package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {

    private val good = "11".repeat(32)  // certificate of the real app
    private val evil = "22".repeat(32)  // certificate of a lookalike app

    private fun site(title: String, url: String) = VaultEntry(title = title, url = url)

    private fun titles(list: List<VaultEntry>) = list.map { it.title }.toSet()

    // ---- host ----

    @Test fun hostOfBareDomainWithPath() {
        assertEquals("example.com", Match.host("example.com/x"))
    }

    @Test fun hostIsLowercasedAndPortAndSchemeStripped() {
        assertEquals("example.com", Match.host("https://Example.com:8443/x"))
        assertEquals("login.example.com", Match.host("http://Login.Example.COM"))
    }

    @Test fun userinfoIsNotTheHost() {
        assertEquals("evil.net", Match.host("example.com@evil.net"))
        assertEquals("evil.net", Match.host("https://example.com@evil.net/"))
    }

    @Test fun badInputHostIsNull() {
        assertNull(Match.host(""))
        assertNull(Match.host("   "))
        assertNull(Match.host("https://"))
    }

    // ---- domainMatches ----

    @Test fun sameHostMatches() {
        assertTrue(Match.domainMatches("example.com", "example.com"))
    }

    @Test fun subdomainOfSavedMatches() {
        assertTrue(Match.domainMatches("example.com", "login.example.com"))
        assertTrue(Match.domainMatches("example.com", "a.b.example.com"))
    }

    @Test fun parentOfSavedDoesNotMatch() {
        assertFalse(Match.domainMatches("login.example.com", "example.com"))
    }

    @Test fun lookalikeExampleComDotEvilNetDoesNotMatch() {
        assertFalse(Match.domainMatches("example.com", "example.com.evil.net"))
    }

    @Test fun lookalikeNotExampleComDoesNotMatch() {
        assertFalse(Match.domainMatches("example.com", "notexample.com"))
    }

    @Test fun lookalikeUserinfoDoesNotMatch() {
        // host("example.com@evil.net") is evil.net, so the visited host is evil.net
        val visited = Match.host("example.com@evil.net")!!
        assertEquals("evil.net", visited)
        assertFalse(Match.domainMatches("example.com", visited))
    }

    @Test fun suffixWithoutDotBoundaryDoesNotMatch() {
        assertFalse(Match.domainMatches("example.com", "evilexample.com"))
    }

    @Test fun singleLabelMatchesOnlyItself() {
        assertTrue(Match.domainMatches("com", "com"))
        assertFalse(Match.domainMatches("com", "example.com"))
        assertFalse(Match.domainMatches("com", "a.b.com"))
    }

    @Test fun ipAddressMatchesOnlyItself() {
        assertTrue(Match.domainMatches("10.0.0.1", "10.0.0.1"))
        assertFalse(Match.domainMatches("10.0.0.1", "x.10.0.0.1"))
        assertFalse(Match.domainMatches("10.0.0.1", "10.0.0.2"))
    }

    @Test fun caseDoesNotMatter() {
        assertTrue(Match.domainMatches("Example.COM", "example.com"))
        assertTrue(Match.domainMatches("example.com", "LOGIN.Example.com"))
    }

    @Test fun trailingDotIsIgnored() {
        assertTrue(Match.domainMatches("example.com.", "login.example.com."))
        assertTrue(Match.domainMatches("example.com", "example.com."))
    }

    // ---- forWeb ----

    private val webEntries = listOf(
        site("A", "https://example.com/login"),   // saved on example.com
        site("B", "example.com"),                  // bare domain form
        site("C", "https://notexample.com"),       // lookalike
        site("D", "https://example.com.evil.net/"), // lookalike
        site("E", "https://example.com@evil.net/"), // lookalike via userinfo
        site("F", ""),                             // no url
        site("G", "https://login.example.com"),    // narrower saved host
    )

    @Test fun forWebFromSubdomainKeepsParentAndNarrowerSavedHostsOnly() {
        // visited login.example.com: A, B (example.com covers it), G (same host). Not C, D, E, F.
        assertEquals(setOf("A", "B", "G"), titles(Match.forWeb(webEntries, "login.example.com")))
    }

    @Test fun forWebFromLookalikeFindsOnlyEntriesOnItsRealHosts() {
        // example.com.evil.net is a subdomain of evil.net (E), but not of example.com (A, B)
        assertEquals(setOf("D", "E"), titles(Match.forWeb(webEntries, "example.com.evil.net")))
    }

    @Test fun forWebFromNotExampleFindsOnlyTheSavedNotExample() {
        assertEquals(setOf("C"), titles(Match.forWeb(webEntries, "notexample.com")))
    }

    @Test fun forWebFromRealDomainExcludesLookalikes() {
        val got = titles(Match.forWeb(webEntries, "example.com"))
        assertEquals(setOf("A", "B"), got)
        assertFalse(got.contains("E"))
    }

    @Test fun forWebEntryWithoutUrlNeverMatches() {
        assertFalse(titles(Match.forWeb(webEntries, "example.com")).contains("F"))
    }

    // ---- forApp ----

    private val pkg = "com.bank.app"

    private val appEntries = listOf(
        VaultEntry(title = "P", apps = listOf(AppLink(pkg, good))),
        VaultEntry(title = "Q", apps = listOf(AppLink(pkg, evil))),                 // same pkg, other cert
        VaultEntry(title = "R", apps = listOf(AppLink("com.other", good))),          // same cert, other pkg
        VaultEntry(title = "S", url = pkg),                                           // pkg only in url
        VaultEntry(title = pkg, apps = emptyList()),                                  // pkg only as title
        VaultEntry(title = "T", apps = listOf(AppLink(pkg, evil), AppLink(pkg, good))),
        VaultEntry(title = "U", url = "https://bank.com"),
    )

    @Test fun forAppNeedsSamePackageAndKnownCertificate() {
        assertEquals(setOf("P", "T"), titles(Match.forApp(appEntries, pkg, setOf(good))))
    }

    @Test fun forAppWithLookalikeCertificateGetsOnlyThatLink() {
        assertEquals(setOf("Q", "T"), titles(Match.forApp(appEntries, pkg, setOf(evil))))
    }

    @Test fun forAppNeverMatchesPackageByUrlOrTitle() {
        val got = titles(Match.forApp(appEntries, pkg, setOf(good, evil)))
        assertFalse(got.contains("S"))
        assertFalse(got.contains(pkg))
        assertFalse(got.contains("U"))
    }

    @Test fun sameCertificateOnAnotherPackageDoesNotLeakToThisPackage() {
        assertEquals(setOf("R"), titles(Match.forApp(appEntries, "com.other", setOf(good))))
        assertTrue(Match.forApp(appEntries, pkg, setOf("33".repeat(32))).isEmpty())
    }

    @Test fun forAppWithNoCertificatesMatchesNothing() {
        assertTrue(Match.forApp(appEntries, pkg, emptySet()).isEmpty())
    }
}

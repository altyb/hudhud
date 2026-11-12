package dev.altayeb.hudhud.vault

object Match {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val LABELS = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Reads a host the way a browser does: userinfo dropped, `\` ends the host, port removed. */
    fun host(url: String): String? {
        var s = url.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        s = s.replaceFirst(SCHEME, "")
        val end = s.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }
        if (end >= 0) s = s.substring(0, end)
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val rest = s.substring(close + 1)
            if (rest.isNotEmpty() && !(rest.startsWith(":") && rest.drop(1).all(Char::isDigit))) return null
            return s.substring(0, close + 1).lowercase()
        }
        val colon = s.indexOf(':')
        if (colon >= 0) {
            if (!s.substring(colon + 1).all(Char::isDigit)) return null
            s = s.substring(0, colon)
        }
        s = s.trimEnd('.')
        if (s.isEmpty()) return null
        val ascii = runCatching { java.net.IDN.toASCII(s).lowercase() }.getOrNull() ?: return null
        return ascii.takeIf { LABELS.matches(it) }
    }

    fun domainMatches(saved: String, visited: String): Boolean {
        val a = host(saved) ?: return false
        val b = host(visited) ?: return false
        if (a == b) return true
        if ('.' !in a || a.startsWith("[") || IPV4.matches(a)) return false
        return b.endsWith(".$a")
    }

    fun forWeb(entries: List<VaultEntry>, visitedHost: String): List<VaultEntry> =
        entries.filter { it.url.isNotBlank() && domainMatches(it.url, visitedHost) }

    fun forApp(entries: List<VaultEntry>, pkg: String, certs: Set<String>): List<VaultEntry> =
        entries.filter { e -> e.apps.any { it.pkg == pkg && it.certSha256 in certs } }
}

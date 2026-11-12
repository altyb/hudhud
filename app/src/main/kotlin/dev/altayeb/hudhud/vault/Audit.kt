package dev.altayeb.hudhud.vault

enum class Issue { WEAK, REUSED, OLD, NO_PASSWORD }
data class Finding(val entry: VaultEntry, val issues: Set<Issue>)

object Audit {
    private const val YEAR = 365L * 24 * 3600

    fun run(entries: List<VaultEntry>, nowEpoch: Long): List<Finding> {
        val counts = entries.filter { it.password.isNotEmpty() }.groupingBy { it.password }.eachCount()
        return entries.mapNotNull { e ->
            val found = HashSet<Issue>()
            if (e.password.isEmpty()) found.add(Issue.NO_PASSWORD)
            else {
                if (Generator.entropyBits(e.password) < 50) found.add(Issue.WEAK)
                if ((counts[e.password] ?: 0) > 1) found.add(Issue.REUSED)
            }
            if (e.passwordChanged > 0 && nowEpoch - e.passwordChanged > YEAR) found.add(Issue.OLD)
            if (found.isEmpty()) null else Finding(e, found)
        }
    }
}

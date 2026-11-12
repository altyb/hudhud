package dev.altayeb.hudhud.vault

object Merge {
    /** Union by UUID; the newer `modified` wins and a tie keeps [local]. Nothing is deleted, so a merge can never lose an entry. */
    fun merge(local: List<VaultEntry>, other: List<VaultEntry>): List<VaultEntry> {
        val out = LinkedHashMap<java.util.UUID, VaultEntry>()
        for (e in local) out[e.uuid] = e
        for (e in other) {
            val mine = out[e.uuid]
            if (mine == null || e.modified > mine.modified) out[e.uuid] = e
        }
        return out.values.toList()
    }
}

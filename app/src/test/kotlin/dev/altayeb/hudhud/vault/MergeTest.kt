package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MergeTest {

    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun e(uuid: UUID, title: String, modified: Long) = VaultEntry(uuid = uuid, title = title, modified = modified)

    @Test fun unionKeepsEntriesFromBothSides() {
        val out = Merge.merge(listOf(e(a, "A", 1)), listOf(e(b, "B", 1)))
        assertEquals(setOf(a, b), out.map { it.uuid }.toSet())
        assertEquals(2, out.size)
    }

    @Test fun newerOtherWinsOverLocal() {
        val out = Merge.merge(listOf(e(a, "old", 10)), listOf(e(a, "new", 20)))
        assertEquals(1, out.size)
        assertEquals("new", out.single().title)
    }

    @Test fun olderOtherLosesToLocal() {
        val out = Merge.merge(listOf(e(a, "local", 20)), listOf(e(a, "other", 10)))
        assertEquals("local", out.single().title)
    }

    @Test fun tieKeepsLocal() {
        val out = Merge.merge(listOf(e(a, "local", 15)), listOf(e(a, "other", 15)))
        assertEquals("local", out.single().title)
    }

    @Test fun nothingIsDroppedWhenOneSideIsEmpty() {
        val local = listOf(e(a, "A", 1), e(b, "B", 2))
        assertEquals(local.map { it.uuid }, Merge.merge(local, emptyList()).map { it.uuid })
        assertEquals(local.map { it.uuid }, Merge.merge(emptyList(), local).map { it.uuid })
    }

    @Test fun nothingIsDroppedFromEitherSideOnMixedMerge() {
        val local = listOf(e(a, "A", 5), e(b, "B", 5))
        val other = listOf(e(b, "B2", 9), e(c, "C", 1))
        val out = Merge.merge(local, other).associateBy { it.uuid }
        assertEquals(setOf(a, b, c), out.keys)
        assertEquals("B2", out.getValue(b).title)   // newer copy of b wins
        assertEquals("A", out.getValue(a).title)    // local-only entry survives
        assertEquals("C", out.getValue(c).title)    // other-only entry survives
    }

    @Test fun emptyMergeIsEmpty() {
        assertTrue(Merge.merge(emptyList(), emptyList()).isEmpty())
    }
}

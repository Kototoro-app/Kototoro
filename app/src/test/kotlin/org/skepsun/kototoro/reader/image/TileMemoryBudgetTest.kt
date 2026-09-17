package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.PageId

class TileMemoryBudgetTest {

    private val pageId = PageId(1L)

    private fun key(id: Long, row: Int = 0): TileKey =
        TileKey(pageId = PageId(id), kind = TileKind.LATTICE, sampleSize = 1, col = 0, row = row)

    private class ManualClock {
        var tick = 0L
        fun advance() {
            tick += 10L
        }
    }

    @Test
    fun `charges within budget evict nothing`() {
        val budget = TileMemoryBudget(maxBytes = 1000L)
        assertTrue(budget.charge(key(1), 400L, TileRetention.NEARBY).isEmpty())
        assertTrue(budget.charge(key(2), 400L, TileRetention.NEARBY).isEmpty())

        assertEquals(800L, budget.residentBytes)
        assertEquals(2, budget.size)
    }

    @Test
    fun `over-budget charge evicts least recently used tile of the same class`() {
        val clock = ManualClock()
        val budget = TileMemoryBudget(maxBytes = 1000L, clock = { clock.tick })
        val a = key(1)
        val b = key(2)
        val c = key(3)

        budget.charge(a, 400L, TileRetention.NEARBY)
        clock.advance()
        budget.charge(b, 400L, TileRetention.NEARBY)
        clock.advance()

        val evicted = budget.charge(c, 400L, TileRetention.NEARBY)

        assertEquals(listOf(a), evicted)
        assertFalse(budget.contains(a))
        assertTrue(budget.contains(b))
        assertTrue(budget.contains(c))
        assertEquals(800L, budget.residentBytes)
    }

    @Test
    fun `eviction order prefers CACHE over STANDBY over NEARBY regardless of recency`() {
        val clock = ManualClock()
        val budget = TileMemoryBudget(maxBytes = 1000L, clock = { clock.tick })
        val standby = key(1)
        val cache = key(2)

        // standby is older, cache is newer: class priority must beat recency.
        budget.charge(standby, 300L, TileRetention.STANDBY)
        clock.advance()
        budget.charge(cache, 300L, TileRetention.CACHE) // resident 600 <= 1000, stable
        clock.advance()

        // Charging 800 goes to 1400: CACHE (newer!) must be evicted before STANDBY (older).
        val evicted = budget.charge(key(3), 800L, TileRetention.NEARBY)

        assertEquals(listOf(cache, standby), evicted)
        assertEquals(800L, budget.residentBytes)
    }

    @Test
    fun `VISIBLE tiles are pinned and never evicted under pressure`() {
        val budget = TileMemoryBudget(maxBytes = 1000L)

        budget.charge(key(1), 400L, TileRetention.VISIBLE)
        budget.charge(key(2), 400L, TileRetention.VISIBLE)
        val evicted = budget.charge(key(3), 400L, TileRetention.VISIBLE)

        assertTrue(evicted.isEmpty())
        assertEquals(1200L, budget.residentBytes) // over budget but pinned

        // A newly charged NEARBY tile is the only evictable one.
        val nearby = key(4)
        val evictedNearby = budget.charge(nearby, 400L, TileRetention.NEARBY)
        assertEquals(listOf(nearby), evictedNearby)
        assertTrue(budget.contains(key(1)))
        assertTrue(budget.contains(key(2)))
        assertTrue(budget.contains(key(3)))
        assertEquals(1200L, budget.residentBytes)
    }

    @Test
    fun `oversized charge of an evictable tile evicts itself`() {
        val budget = TileMemoryBudget(maxBytes = 100L)

        val evicted = budget.charge(key(1), 200L, TileRetention.CACHE)

        assertEquals(listOf(key(1)), evicted)
        assertFalse(budget.contains(key(1)))
        assertEquals(0L, budget.residentBytes)
    }

    @Test
    fun `oversized charge of a VISIBLE tile stays resident`() {
        val budget = TileMemoryBudget(maxBytes = 100L)

        val evicted = budget.charge(key(1), 200L, TileRetention.VISIBLE)

        assertTrue(evicted.isEmpty())
        assertEquals(200L, budget.residentBytes)
    }

    @Test
    fun `re-charging the same key replaces cost and retention`() {
        val budget = TileMemoryBudget(maxBytes = 1000L)
        val k = key(1)

        budget.charge(k, 100L, TileRetention.CACHE)
        budget.charge(k, 500L, TileRetention.NEARBY)

        assertEquals(500L, budget.residentBytes)
        assertEquals(TileRetention.NEARBY, budget.retentionOf(k))
    }

    @Test
    fun `updateRetention changes eviction class and touch refreshes recency`() {
        val clock = ManualClock()
        val budget = TileMemoryBudget(maxBytes = 1000L, clock = { clock.tick })
        val demoted = key(1)
        val idle = key(2)

        budget.charge(demoted, 600L, TileRetention.VISIBLE)
        clock.advance()
        budget.charge(idle, 600L, TileRetention.VISIBLE)
        clock.advance()

        // Demote one tile; the other stays pinned visible.
        budget.updateRetention(demoted, TileRetention.STANDBY)

        // Charging a third tile goes over budget: the only evictable tile is dropped.
        val evicted = budget.charge(key(3), 300L, TileRetention.VISIBLE)
        assertEquals(listOf(demoted), evicted)
        assertTrue(budget.contains(idle))

        // touch() refreshes LRU order among same-class entries.
        val older = key(4)
        val newer = key(5)
        budget.charge(older, 50L, TileRetention.CACHE) // resident 950
        clock.advance()
        budget.charge(newer, 50L, TileRetention.CACHE) // resident 1000 = budget, stable
        clock.advance()
        budget.touch(older) // older becomes most-recently used
        clock.advance()
        val evictedLru = budget.charge(key(6), 40L, TileRetention.CACHE) // 1040 -> trim
        assertEquals(listOf(newer), evictedLru) // older was touched, newer is now LRU
    }

    @Test
    fun `release and releaseWhere drop entries and report freed bytes`() {
        val budget = TileMemoryBudget(maxBytes = 100_000L)
        val a = TileKey(PageId(1L), TileKind.LATTICE, 1, 0, 0)
        val b = TileKey(PageId(2L), TileKind.LATTICE, 1, 0, 0)
        val c = TileKey(PageId(2L), TileKind.OVERVIEW, 1, 0, 0)

        budget.charge(a, 100L, TileRetention.NEARBY)
        budget.charge(b, 200L, TileRetention.NEARBY)
        budget.charge(c, 300L, TileRetention.NEARBY)

        assertEquals(200L, budget.release(b))
        assertEquals(0L, budget.release(b)) // already gone

        // Only c (300) remains for pageId 2 after b was released above.
        assertEquals(300L, budget.releaseWhere { it.pageId == PageId(2L) })
        assertEquals(100L, budget.residentBytes)

        assertEquals(100L, budget.clear())
        assertEquals(0L, budget.residentBytes)
        assertNull(budget.retentionOf(a))
    }
}

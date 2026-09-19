package org.skepsun.kototoro.reader.image

/**
 * Retention classes governing eviction priority (plan: four-level intelligent eviction).
 *
 * Eviction order under memory pressure runs strictly from the lowest keep
 * priority upward: [CACHE] → [STANDBY] → [NEARBY]. [VISIBLE] tiles are pinned
 * and never evicted by pressure — an over-budget burst of visible tiles is
 * preferred over dropping pixels the user is currently looking at.
 */
enum class TileRetention {
    /** Currently intersecting the viewport. Pinned; never evicted by pressure. */
    VISIBLE,

    /** Inside the lookahead/prefetch window but not yet visible. */
    NEARBY,

    /** Resident but outside the most recent request set (first demotion stage). */
    STANDBY,

    /** Idle; kept only while budget allows (evicted first). */
    CACHE,
}

/**
 * Byte-budgeted tile residency ledger with four-level intelligent eviction.
 *
 * Pure bookkeeping: it tracks costs and retention classes and decides **which
 * keys to evict**; the owner ([ReaderTileManager]) maps returned keys to actual
 * payload releases. All methods are thread-safe.
 *
 * Within one retention class, victims are chosen least-recently-touched first.
 * The clock is injectable for deterministic tests.
 */
class TileMemoryBudget(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { System.nanoTime() },
) {
    init {
        require(maxBytes > 0) { "maxBytes must be > 0: $maxBytes" }
    }

    private class Entry(
        val costBytes: Long,
        var retention: TileRetention,
        var accessTick: Long,
    )

    private val entries = HashMap<TileKey, Entry>()

    /** Current sum of resident tile costs. May exceed [maxBytes] while VISIBLE tiles are pinned. */
    val residentBytes: Long
        get() = synchronized(lock) { residentBytesLocked }

    /** Number of currently tracked tiles. */
    val size: Int
        get() = synchronized(lock) { entries.size }

    private var residentBytesLocked: Long = 0L
    private val lock = Any()

    /**
     * Charges [costBytes] for [key] at [retention], replacing any previous charge
     * for the same key, then trims the budget.
     *
     * @return keys that had to be evicted to return within budget (never VISIBLE
     *   keys; may contain [key] itself when its own cost exceeds the budget and
     *   its retention is evictable).
     */
    fun charge(key: TileKey, costBytes: Long, retention: TileRetention): List<TileKey> {
        require(costBytes >= 0) { "costBytes must be >= 0: $costBytes" }
        return synchronized(lock) {
            entries.remove(key)?.let { residentBytesLocked -= it.costBytes }
            entries[key] = Entry(costBytes, retention, clock())
            residentBytesLocked += costBytes
            trimLocked()
        }
    }

    /**
     * Re-labels an existing entry's retention and refreshes its LRU tick.
     * No-op for unknown keys.
     */
    fun updateRetention(key: TileKey, retention: TileRetention) {
        synchronized(lock) {
            entries[key]?.let {
                it.retention = retention
                it.accessTick = clock()
            }
        }
    }

    /** Refreshes the LRU tick of an existing entry. No-op for unknown keys. */
    fun touch(key: TileKey) {
        synchronized(lock) {
            entries[key]?.accessTick = clock()
        }
    }

    /** Releases [key]; returns the cost released (0 when absent). */
    fun release(key: TileKey): Long = synchronized(lock) {
        entries.remove(key)?.let {
            residentBytesLocked -= it.costBytes
            it.costBytes
        } ?: 0L
    }

    /** Releases every entry matching [predicate]; returns the total cost released. */
    fun releaseWhere(predicate: (TileKey) -> Boolean): Long = synchronized(lock) {
        var released = 0L
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (predicate(key)) {
                released += entry.costBytes
                residentBytesLocked -= entry.costBytes
                iterator.remove()
            }
        }
        released
    }

    /** Drops all entries; returns the total cost released. */
    fun clear(): Long = synchronized(lock) {
        val released = residentBytesLocked
        entries.clear()
        residentBytesLocked = 0L
        released
    }

    fun contains(key: TileKey): Boolean = synchronized(lock) { entries.containsKey(key) }

    fun retentionOf(key: TileKey): TileRetention? = synchronized(lock) { entries[key]?.retention }

    /** Trims resident bytes back within [maxBytes]. Never evicts VISIBLE entries. */
    fun trim(): List<TileKey> = synchronized(lock) { trimLocked() }

    private fun trimLocked(): List<TileKey> {
        if (residentBytesLocked <= maxBytes) return emptyList()
        val victims = entries.entries
            .asSequence()
            .filter { it.value.retention != TileRetention.VISIBLE }
            .sortedWith(
                compareBy(
                    { -it.value.retention.ordinal }, // CACHE (3) evicted before STANDBY (2) before NEARBY (1)
                    { it.value.accessTick }, // LRU first within the same class
                ),
            )
            .iterator()

        val evicted = ArrayList<TileKey>()
        while (residentBytesLocked > maxBytes && victims.hasNext()) {
            val (key, entry) = victims.next()
            entries.remove(key)
            residentBytesLocked -= entry.costBytes
            evicted.add(key)
        }
        return evicted
    }

    companion object {
        /**
         * Residency cap, sized to the working set a zoomed paged view actually pins.
         *
         * Not the planner's single-bitmap budget ([TilePolicy.DEFAULT_WORKING_SET_COST_BUDGET_BYTES],
         * 64MB): VISIBLE tiles are pinned regardless of the cap, so a cap below the working set only
         * makes the ledger trim halo tiles that the next frame asks for again. Measured at 2x when the
         * per-tile payload shrank by a third (seam padding 128 -> 8): evictions 50 -> 140 and decode
         * launches 239 -> 624 per run, with the pinned working set steady at ~207MB and the frame
         * overrun P99 going from -0.8ms to +95ms. Sizing the cap above that working set removes the
         * churn; the visible set still bounds residency in practice.
         */
        const val DEFAULT_MAX_BYTES = 256L * 1024L * 1024L
    }
}

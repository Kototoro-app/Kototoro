package org.skepsun.kototoro.core.prefs

/**
 * How the main navigation list behaves, shared by Android's bottom bar and the Windows rail.
 *
 * Both platforms edit the same shape: an ordered list of destination keys, persisted as one comma-joined string
 * under `nav_main`, never carrying duplicates and never longer than [MAX_ITEMS]. Android's [NavItem] enum and the
 * Windows rail's own keys stay platform-side because they carry resource ids and icons; everything about *which*
 * keys a stored string means, how an edit lands and when the list falls back to its default lives here.
 */
object MainNavigationRules {
    /** Android's `MAX_MAIN_NAV_ITEM_COUNT`: a phone bottom bar and the Windows rail both stop here. */
    const val MAX_ITEMS = 5

    /** Drops repeats (the first occurrence wins) and cuts the list to [MAX_ITEMS]. */
    fun <T> limit(items: Iterable<T>): List<T> = items.distinct().take(MAX_ITEMS)

    /**
     * Moves the item at [from] onto [to], shifting everything in between — what a drag of a reorder handle does.
     * Out-of-range indexes, including a move onto the same slot, return the list unchanged.
     */
    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in items.indices || to !in items.indices) return items
        val result = items.toMutableList()
        val moved = result.removeAt(from)
        result.add(to, moved)
        return result
    }

    /**
     * One step up ([direction] = -1) or down ([direction] = 1). Android's drag handle reports the raw offset, so a
     * drag past either end lands on that edge instead of doing nothing; a single step at the edge lands on itself
     * and returns the list unchanged.
     */
    fun <T> step(items: List<T>, item: T, direction: Int): List<T> {
        val from = items.indexOf(item)
        if (from < 0 || direction == 0) return items
        return move(items, from, (from + direction).coerceIn(0, items.lastIndex))
    }

    /** Appends [item] when there is room left and it is not already configured. */
    fun <T> add(items: List<T>, item: T): List<T> {
        if (!canAdd(items) || item in items) return items
        return items + item
    }

    /** Removes [item]; the last remaining item cannot be removed, so [fallback] takes its place instead. */
    fun <T> remove(items: List<T>, item: T, fallback: T): List<T> {
        val result = items - item
        return result.ifEmpty { listOf(fallback) }
    }

    /** The picker's contents: everything not configured yet, in [all]'s order, minus [excluded]. */
    fun <T> available(
        all: Iterable<T>,
        configured: Collection<T>,
        excluded: Collection<T> = emptyList(),
    ): List<T> = all.filterNot { it in configured || it in excluded }

    /** Whether another item still fits; the add row turns into a hint at the cap. */
    fun <T> canAdd(items: Collection<T>): Boolean = items.size < MAX_ITEMS

    /**
     * Reads a stored comma list. Unknown or [excluded] keys are skipped, and a blank, unreadable or empty result
     * falls back to [fallback] so a broken document never leaves the app without navigation.
     */
    fun <T> parse(
        raw: String?,
        resolve: (String) -> T?,
        fallback: List<T>,
        excluded: Collection<T> = emptyList(),
    ): List<T> {
        if (raw.isNullOrBlank()) return limit(fallback)
        val parsed = raw.split(',')
            .mapNotNull { key -> resolve(key.trim()) }
            .filterNot { it in excluded }
        return limit(if (parsed.isEmpty()) fallback else parsed)
    }

    /** The single preference string: keys in display order, comma-separated — Android's `nav_main` format. */
    fun <T> format(items: Iterable<T>, key: (T) -> String = { it.toString() }): String =
        items.joinToString(",") { key(it) }
}

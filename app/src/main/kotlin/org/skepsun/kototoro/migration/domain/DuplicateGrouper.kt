package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.data.LibraryTrackingId
import org.skepsun.kototoro.parsers.model.ContentType

/** Android's title keys and content families for the shared [DuplicateGroupingRules]. */
object DuplicateGrouper {

    fun group(
        rows: List<LibraryRow>,
        unhealthySources: Set<String>,
        ignoredKeys: Set<String>,
        trackingIds: List<LibraryTrackingId> = emptyList(),
    ): List<LibraryDuplicateGroup> = DuplicateGroupingRules.group(
        rows = rows,
        unhealthySources = unhealthySources,
        ignoredKeys = ignoredKeys,
        keysOf = DuplicateMatcher::keys,
        familyOf = { name -> ContentType.entries.firstOrNull { it.name == name }?.contentFamily()?.name },
        trackingIds = trackingIds,
    )

    /** True when [a] was read further than [b]. */
    fun isFurther(a: LibraryRow, b: LibraryRow): Boolean = DuplicateGroupingRules.isFurther(a, b)
}

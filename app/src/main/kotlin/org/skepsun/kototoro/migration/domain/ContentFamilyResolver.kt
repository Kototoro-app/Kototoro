package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Content family of an entry. An unloaded source (the usual reason to migrate) often
 * reports [ContentType.OTHER], so the type stored with the entry is used as a fallback.
 * [ContentTypeFamily.OTHER] means "unknown": callers then offer sources of every family.
 */
/**
 * Family used to pick target sources for each entry of one migration. Entries whose family
 * is unknown join the most common known family instead of forming an "other" group that
 * would offer every source; only an all-unknown selection stays [ContentTypeFamily.OTHER].
 */
fun assignFamilies(resolved: Map<Long, ContentTypeFamily>): Map<Long, ContentTypeFamily> {
    val fallback = resolved.values
        .filter { it != ContentTypeFamily.OTHER }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
        ?: return resolved
    return resolved.mapValues { (_, family) -> if (family == ContentTypeFamily.OTHER) fallback else family }
}

/** Display order of family sections. */
val ContentTypeFamily.sectionOrder: Int
    get() = when (this) {
        ContentTypeFamily.MANGA -> 0
        ContentTypeFamily.NOVEL -> 1
        ContentTypeFamily.VIDEO -> 2
        ContentTypeFamily.OTHER -> 3
    }

fun resolveContentFamily(sourceType: ContentType, storedType: String?): ContentTypeFamily {
    val fromSource = sourceType.contentFamily()
    if (fromSource != ContentTypeFamily.OTHER) return fromSource
    return storedType
        ?.let { name -> ContentType.entries.firstOrNull { it.name == name } }
        ?.contentFamily()
        ?: ContentTypeFamily.OTHER
}

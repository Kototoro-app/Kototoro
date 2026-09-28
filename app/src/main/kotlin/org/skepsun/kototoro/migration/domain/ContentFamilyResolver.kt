package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Content family of an entry. An unloaded source (the usual reason to migrate) often
 * reports [ContentType.OTHER], so the type stored with the entry is used as a fallback.
 * [ContentTypeFamily.OTHER] means "unknown": callers then offer sources of every family.
 */
fun resolveContentFamily(sourceType: ContentType, storedType: String?): ContentTypeFamily {
    val fromSource = sourceType.contentFamily()
    if (fromSource != ContentTypeFamily.OTHER) return fromSource
    return storedType
        ?.let { name -> ContentType.entries.firstOrNull { it.name == name } }
        ?.contentFamily()
        ?: ContentTypeFamily.OTHER
}

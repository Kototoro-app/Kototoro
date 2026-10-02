package org.skepsun.kototoro.parsers.model

import kotlin.jvm.JvmField

/**
 * Group of tags for UI presentation.
 * Clients may fall back to [ContentListFilterOptions.availableTags] if not supported.
 */
public data class ContentTagGroup(
    @JvmField val title: String,
    @JvmField val tags: Set<ContentTag>,
    @JvmField val isExclusive: Boolean = false,
)

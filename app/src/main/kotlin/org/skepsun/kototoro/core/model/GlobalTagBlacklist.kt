package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.Content
import java.util.Locale

/**
 * Android's global tag blacklist: the matching rules live in the shared [TagTitleBlacklist], and this class adds the
 * parser `Content` overload plus locale-pinned normalization for the stored keys.
 */
class GlobalTagBlacklist(tags: Collection<String>) : TagBlacklist {

    private val rules = TagTitleBlacklist(tags)

    val isEmpty: Boolean
        get() = rules.isEmpty

    operator fun contains(content: Content): Boolean = content.tags.any { containsTagTitle(it.title) }

    /**
     * True when a single tag title is blacklisted — the per-tag half of
     * [contains] without building a `Content`. The favourites snapshot uses this to
     * apply the blacklist to its pre-resolved display tags.
     */
    override fun containsTagTitle(title: String): Boolean = rules.containsTagTitle(title)

    fun filter(contents: List<Content>): List<Content> = if (isEmpty) {
        contents
    } else {
        contents.filterNot { it in this }
    }

    companion object {
        const val RAW_TAG_PREFIX = TagTitleBlacklist.RAW_TAG_PREFIX

        fun rawTagKey(title: String): String = TagTitleBlacklist.rawTagKey(title)

        internal fun normalizeTag(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}

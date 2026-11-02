package org.skepsun.kototoro.core.model

/**
 * The global tag blacklist as a pure rule. The stored selection may hold taxonomy IDs, source aliases or free-form
 * `raw:` keys; matching a tag title is an exact, case- and whitespace-insensitive comparison against the resolved
 * permanent IDs plus the raw keys — no fuzzy matching, so a short word never hides a longer tag.
 *
 * Shared so every platform hides the same works for the same selection; Android's `GlobalTagBlacklist` adds the
 * parser `Content` overload on top, and the desktop shelves ask about pre-resolved tag titles directly.
 */
class TagTitleBlacklist(tags: Collection<String>) : TagBlacklist {

    private val taxonomyIds: Set<String>
    private val normalizedRawTags: Set<String>

    init {
        val ids = LinkedHashSet<String>(tags.size)
        val raw = LinkedHashSet<String>(tags.size)
        tags.forEach { selected ->
            when {
                selected.startsWith(RAW_TAG_PREFIX) -> raw += normalizeTag(selected.removePrefix(RAW_TAG_PREFIX))
                TagTaxonomy.find(selected) != null -> ids += selected
                else -> {
                    val resolved = TagTaxonomy.resolve(selected).mapTo(LinkedHashSet(), TaxonomyTag::id)
                    if (resolved.isEmpty()) raw += normalizeTag(selected) else ids += resolved
                }
            }
        }
        raw.remove("")
        taxonomyIds = ids
        normalizedRawTags = raw
    }

    val isEmpty: Boolean get() = taxonomyIds.isEmpty() && normalizedRawTags.isEmpty()

    override fun containsTagTitle(title: String): Boolean = when {
        isEmpty -> false
        else -> normalizeTag(title) in normalizedRawTags ||
            TagTaxonomy.resolve(title).any { it.id in taxonomyIds }
    }

    /** One work can carry several tags; the shelves ask once per work instead of building a list of hits. */
    fun containsAnyTitle(titles: Iterable<String>): Boolean = titles.any(::containsTagTitle)

    companion object {
        const val RAW_TAG_PREFIX = "raw:"

        fun rawTagKey(title: String): String = RAW_TAG_PREFIX + normalizeTag(title)

        fun normalizeTag(value: String): String = value.trim().lowercase()
    }
}

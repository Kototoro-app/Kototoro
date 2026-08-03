package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.Content
import java.util.Locale

class GlobalTagBlacklist(tags: Collection<String>) {

	private val taxonomyIds = HashSet<String>(tags.size)
	private val normalizedRawTags = HashSet<String>(tags.size)

	init {
		tags.forEach { selectedTag ->
			when {
				selectedTag.startsWith(RAW_TAG_PREFIX) -> {
					normalizedRawTags += normalizeTag(selectedTag.removePrefix(RAW_TAG_PREFIX))
				}

				KototoroTaxonomy.find(selectedTag) != null -> taxonomyIds += selectedTag
				else -> {
					val resolvedIds = KototoroTaxonomy.resolve(selectedTag).mapTo(LinkedHashSet(), TaxonomyTag::id)
					if (resolvedIds.isEmpty()) {
						normalizedRawTags += normalizeTag(selectedTag)
					} else {
						taxonomyIds += resolvedIds
					}
				}
			}
		}
		normalizedRawTags.remove("")
	}

	val isEmpty: Boolean
		get() = taxonomyIds.isEmpty() && normalizedRawTags.isEmpty()

	operator fun contains(content: Content): Boolean = content.tags.any { tag ->
		val normalizedTitle = normalizeTag(tag.title)
		normalizedTitle in normalizedRawTags ||
			KototoroTaxonomy.resolve(tag.title).any { it.id in taxonomyIds }
	}

	fun filter(contents: List<Content>): List<Content> = if (isEmpty) {
		contents
	} else {
		contents.filterNot { it in this }
	}

	companion object {
		const val RAW_TAG_PREFIX = "raw:"

		fun rawTagKey(title: String): String = RAW_TAG_PREFIX + normalizeTag(title)

		internal fun normalizeTag(value: String): String = value.trim().lowercase(Locale.ROOT)
	}
}

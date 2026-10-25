package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.model.TagTaxonomy
import org.skepsun.kototoro.core.model.TagTitleBlacklist
import org.skepsun.kototoro.core.model.TagWarnings
import org.skepsun.kototoro.core.model.TaxonomyCategory
import org.skepsun.kototoro.core.model.TaxonomyTag

/**
 * Android's tag rules for the iOS app: the sensitive-category highlight, the global tag blacklist matcher and the
 * tag catalog its selection page lists. Selections use Android's stored form (catalog IDs or `raw:` keys).
 */
object IosTags {
    const val RAW_PREFIX = "raw:"

    /** A tag Android highlights as a sensitive category on the details page. */
    fun isWarned(title: String): Boolean = TagWarnings.isWarned(title)

    fun blacklist(selected: List<String>): IosTagBlacklist = IosTagBlacklist(TagTitleBlacklist(selected))

    /** The catalog by category, labelled in [language] ("zh" or another code for English); deprecated tags left out. */
    fun categories(language: String): List<IosTagCategory> = TagTaxonomy.tags
        .filterNot(TaxonomyTag::deprecated)
        .groupBy(TaxonomyTag::category)
        .entries.sortedBy { it.key.ordinal }
        .map { (category, tags) ->
            IosTagCategory(category.id, category.localizedLabel(language), tags.map { it.toIos(language) })
        }

    /** Whether catalog tag [id] matches [query] by its ID, labels or source aliases. */
    fun matches(id: String, query: String): Boolean = TagTaxonomy.find(id)?.let { TagTaxonomy.search(it, query) } ?: false

    /** The label of a stored selection: the catalog label, or the raw key itself. */
    fun label(selected: String, language: String): String =
        TagTaxonomy.find(selected)?.localizedLabel(language) ?: selected.removePrefix(RAW_PREFIX)

    /** A free-form entry for a title the catalog does not know, or the catalog ID it resolves to. */
    fun selection(title: String): String {
        val trimmed = title.trim()
        return TagTaxonomy.resolve(trimmed).firstOrNull()?.id ?: (RAW_PREFIX + TagTaxonomy.normalize(trimmed))
    }

    private fun TaxonomyTag.toIos(language: String) = IosTag(id, localizedLabel(language),
        if (language == "zh") englishLabel else chineseLabel, aliases.sorted(), adult,
        category == TaxonomyCategory.WARNING)
}

class IosTagBlacklist internal constructor(private val value: TagTitleBlacklist) {
    val isEmpty: Boolean get() = value.isEmpty

    /** Whether a work carrying [titles] is hidden. */
    fun hides(titles: List<String>): Boolean = !value.isEmpty && value.containsAnyTitle(titles)
}

data class IosTagCategory(val id: String, val label: String, val tags: List<IosTag>)

/** A catalog tag: [secondaryLabel] is the other language's name, shown with the aliases on a long press. */
data class IosTag(val id: String, val label: String, val secondaryLabel: String, val aliases: List<String>,
    val adult: Boolean, val warning: Boolean)

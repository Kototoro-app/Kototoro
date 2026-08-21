package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.util.mapToSet

object AniyomiFilterMapper {

    private const val PREFIX_TOP = "top:"
    private const val PREFIX_SORT = "sort:"
    private const val PREFIX_TEXT = "text:"

    fun mapOptions(aniyomiFilters: AnimeFilterList, source: org.skepsun.kototoro.parsers.model.ContentSource): ContentListFilterOptions {
        val tagGroups = mutableListOf<ContentTagGroup>()
        var currentHeader = "General"

        aniyomiFilters.forEach { filter ->
            when (filter) {
                is AnimeFilter.Header -> {
                    currentHeader = filter.name
                }
                is AnimeFilter.Separator -> { }
                is AnimeFilter.Group<*> -> {
                    val checkboxTags = mutableListOf<ContentTag>()
                    (filter.state as? List<*>)?.forEach { subItem ->
                        if (subItem is AnimeFilter<*>) {
                            when (subItem) {
                                is AnimeFilter.Select<*> -> {
                                    val selectTags = mapFilterToTags(subItem, filter.name, source)
                                    if (selectTags.isNotEmpty()) {
                                        tagGroups.add(ContentTagGroup("${filter.name} - ${subItem.name}", selectTags.toSet()))
                                    }
                                }
                                is AnimeFilter.Sort -> {
                                    val sortTags = mapFilterToTags(subItem, filter.name, source)
                                    if (sortTags.isNotEmpty()) {
                                        tagGroups.add(ContentTagGroup("${filter.name} - ${subItem.name}", sortTags.toSet()))
                                    }
                                }
                                is AnimeFilter.Group<*> -> {
                                    checkboxTags.addAll(mapFilterToTags(subItem, filter.name, source))
                                }
                                else -> {
                                    checkboxTags.addAll(mapFilterToTags(subItem, filter.name, source))
                                }
                            }
                        }
                    }
                    if (checkboxTags.isNotEmpty()) {
                        tagGroups.add(ContentTagGroup(filter.name, checkboxTags.toSet()))
                    }
                }
                else -> {
                    val tags = mapFilterToTags(filter, null, source)
                    if (tags.isNotEmpty()) {
                        tagGroups.add(ContentTagGroup(currentHeader, tags.toSet()))
                    }
                }
            }
        }

        val mergedGroups = tagGroups.groupBy { it.title }.map { (title, groups) ->
            ContentTagGroup(title, groups.flatMap { it.tags }.toSet())
        }

        return ContentListFilterOptions(
            availableTags = mergedGroups.flatMap { it.tags }.toSet(),
            tagGroups = mergedGroups
        )
    }

    private fun mapFilterToTags(
        filter: AnimeFilter<*>,
        parentName: String?,
        source: org.skepsun.kototoro.parsers.model.ContentSource,
    ): List<ContentTag> {
        val prefix = if (parentName != null) "$parentName/" else PREFIX_TOP

        return when (filter) {
            is AnimeFilter.CheckBox -> {
                listOf(ContentTag(filter.name, "$prefix${filter.name}", source))
            }
            is AnimeFilter.TriState -> {
                listOf(ContentTag(filter.name, "$prefix${filter.name}", source))
            }
            is AnimeFilter.Select<*> -> {
                filter.values.map { value ->
                    val title = value.toString()
                    ContentTag(title, "$prefix${filter.name}/$title", source)
                }
            }
            is AnimeFilter.Sort -> {
                filter.values.map { value ->
                    ContentTag(value, "$PREFIX_SORT$prefix${filter.name}/$value", source)
                }
            }
            is AnimeFilter.Text -> {
                listOf(ContentTag("📝 ${filter.name}", "$PREFIX_TEXT$prefix${filter.name}", source))
            }
            is AnimeFilter.Group<*> -> {
                val nestedTags = mutableListOf<ContentTag>()
                (filter.state as? List<*>)?.forEach { subItem ->
                    if (subItem is AnimeFilter<*>) {
                        val nestedPrefix = if (parentName != null) "$parentName/${filter.name}" else filter.name
                        nestedTags.addAll(mapFilterToTags(subItem, nestedPrefix, source))
                    }
                }
                nestedTags
            }
            else -> emptyList()
        }
    }

    fun updateAniyomiFilters(aniyomiFilters: AnimeFilterList, kotoFilter: ContentListFilter) {
        val selectedTags = kotoFilter.tags.mapToSet { it.key }
        val excludedTags = kotoFilter.tagsExclude.mapToSet { it.key }

        aniyomiFilters.forEach { filter ->
            when (filter) {
                is AnimeFilter.Group<*> -> {
                    (filter.state as? List<*>)?.forEach { subItem ->
                        val sub = subItem as? AnimeFilter<*> ?: return@forEach
                        updateSingleFilter(sub, filter.name, selectedTags, excludedTags)
                    }
                }
                else -> {
                    updateSingleFilter(filter, null, selectedTags, excludedTags)
                }
            }
        }
    }

    private fun updateSingleFilter(filter: AnimeFilter<*>, parentName: String?, selectedTags: Set<String>, excludedTags: Set<String>) {
        val prefix = if (parentName != null) "$parentName/" else PREFIX_TOP
        when (filter) {
            is AnimeFilter.CheckBox -> {
                val key = "$prefix${filter.name}"
                filter.state = key in selectedTags
            }
            is AnimeFilter.TriState -> {
                val key = "$prefix${filter.name}"
                filter.state = when {
                    key in selectedTags -> AnimeFilter.TriState.STATE_INCLUDE
                    key in excludedTags -> AnimeFilter.TriState.STATE_EXCLUDE
                    else -> AnimeFilter.TriState.STATE_IGNORE
                }
            }
            is AnimeFilter.Select<*> -> {
                filter.values.forEachIndexed { index, value ->
                    val key = "$prefix${filter.name}/$value"
                    if (key in selectedTags) {
                        filter.state = index
                    }
                }
            }
            is AnimeFilter.Sort -> {
                filter.values.forEachIndexed { index, value ->
                    val key = "$PREFIX_SORT$prefix${filter.name}/$value"
                    if (key in selectedTags) {
                        filter.state = AnimeFilter.Sort.Selection(index, filter.state?.ascending ?: false)
                    }
                }
            }
            is AnimeFilter.Text -> {
                val baseKey = "$PREFIX_TEXT$prefix${filter.name}"
                val matchingTag = selectedTags.find { it.startsWith(baseKey) }
                if (matchingTag != null) {
                    val value = if (matchingTag.contains("=")) matchingTag.substringAfter("=") else ""
                    filter.state = value
                }
            }
            is AnimeFilter.Group<*> -> {
                (filter.state as? List<*>)?.forEach { subItem ->
                    if (subItem is AnimeFilter<*>) {
                        val nestedPrefix = if (parentName != null) "$parentName/${filter.name}" else filter.name
                        updateSingleFilter(subItem, nestedPrefix, selectedTags, excludedTags)
                    }
                }
            }
            else -> {}
        }
    }
}

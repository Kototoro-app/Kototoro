package org.skepsun.kototoro.mihon

import android.util.Log
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.util.mapToSet

object MihonFilterMapper {

    private const val TAG = "MihonFilterMapper"
    private const val PREFIX_TOP = "top:"
    private const val PREFIX_SORT = "sort:"
    private const val PREFIX_TEXT = "text:"

    fun mapOptions(mihonFilters: FilterList, source: org.skepsun.kototoro.parsers.model.ContentSource): ContentListFilterOptions {
        val tagGroups = mutableListOf<ContentTagGroup>()
        var currentHeader = "General"

        mihonFilters.forEachIndexed { index, filter ->
            Log.d(TAG, "[mapOptions] filter[$index] type=${filter::class.simpleName} name=${filter.name.take(60)}")
            when (filter) {
                is Filter.Header -> {
                    currentHeader = filter.name
                }
                is Filter.Separator -> { }
                is Filter.Group<*> -> {
                    when (val state = filter.state) {
                        is List<*> -> {
                            val checkboxTags = mutableListOf<ContentTag>()

                            state.forEach { subItem ->
                                if (subItem is Filter<*>) {
                                    when (subItem) {
                                        is Filter.Select<*> -> {
                                            val selectTags = mapFilterToTags(subItem, filter.name, source)
                                            if (selectTags.isNotEmpty()) {
                                                val groupTitle = "${filter.name} - ${subItem.name}"
                                                tagGroups.add(ContentTagGroup(groupTitle, selectTags.toSet()))
                                            }
                                        }
                                        is Filter.Sort -> {
                                            val sortTags = mapFilterToTags(subItem, filter.name, source)
                                            if (sortTags.isNotEmpty()) {
                                                val groupTitle = "${filter.name} - ${subItem.name}"
                                                tagGroups.add(ContentTagGroup(groupTitle, sortTags.toSet()))
                                            }
                                        }
                                        is Filter.Group<*> -> {
                                            val nestedTags = mapFilterToTags(subItem, filter.name, source)
                                            checkboxTags.addAll(nestedTags)
                                        }
                                        else -> {
                                            val tags = mapFilterToTags(subItem, filter.name, source)
                                            checkboxTags.addAll(tags)
                                        }
                                    }
                                }
                            }

                            if (checkboxTags.isNotEmpty()) {
                                tagGroups.add(ContentTagGroup(filter.name, checkboxTags.toSet()))
                            }
                        }
                    }
                }
                else -> {
                    val tags = mapFilterToTags(filter, null, source)
                    if (tags.isNotEmpty()) {
                        Log.d(TAG, "[mapOptions] else: filter type=${filter::class.simpleName} -> ${tags.size} tags")
                        tagGroups.add(ContentTagGroup(currentHeader, tags.toSet()))
                    }
                }
            }
        }

        val mergedGroups = tagGroups.groupBy { it.title }.map { (title, groups) ->
            val allTags = groups.flatMap { it.tags }.toSet()
            ContentTagGroup(title, allTags)
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[mapOptions] DONE: groups=${mergedGroups.size} totalTags=${mergedGroups.flatMap { it.tags }.size}")
            mergedGroups.forEachIndexed { i, g ->
                val preview = g.tags.take(5).joinToString(" | ") { "'${it.title}' (${it.key.take(40)})" }
                Log.d(TAG, "[mapOptions] group[$i] '${g.title}': ${g.tags.size} tags -> $preview")
            }
        }

        return ContentListFilterOptions(
            availableTags = mergedGroups.flatMap { it.tags }.toSet(),
            tagGroups = mergedGroups
        )
    }

    private fun mapFilterToTags(
        filter: Filter<*>,
        parentName: String?,
        source: org.skepsun.kototoro.parsers.model.ContentSource,
    ): List<ContentTag> {
        val prefix = if (parentName != null) "$parentName/" else PREFIX_TOP
        val filterType = filter::class.simpleName
        val parentInfo = parentName ?: "<root>"

        return when (filter) {
            is Filter.CheckBox -> {
                val title = filter.name.cleanTitle()
                val key = "$prefix${filter.name}"
                Log.d(TAG, "[mapFilterToTags] CheckBox parent=$parentInfo name='${filter.name}' cleaned='$title' -> key=$key")
                listOf(ContentTag(title, key, source))
            }
            is Filter.TriState -> {
                val title = filter.name.cleanTitle()
                val key = "$prefix${filter.name}"
                Log.d(TAG, "[mapFilterToTags] TriState parent=$parentInfo name='${filter.name}' cleaned='$title' -> key=$key")
                listOf(ContentTag(title, key, source))
            }
            is Filter.Select<*> -> {
                val values = filter.values
                Log.d(TAG, "[mapFilterToTags] Select parent=$parentInfo name='${filter.name}' values.count=${values.size} values.class=${values::class.simpleName}")
                filter.values.mapIndexedNotNull { idx, value ->
                    val raw = value.toString()
                    val valueClass = value?.javaClass?.name ?: "null"
                    val title = value.cleanTitle()
                    if (title.isEmpty()) {
                        Log.d(TAG, "[mapFilterToTags] Select[$idx] class=$valueClass raw='${raw.take(80)}' -> SKIPPED (fragment)")
                        return@mapIndexedNotNull null
                    }
                    val key = "$prefix${filter.name}/$title"
                    Log.d(TAG, "[mapFilterToTags] Select[$idx] class=$valueClass raw='${raw.take(80)}' cleaned='$title' -> key=$key")
                    ContentTag(title, key, source)
                }
            }
            is Filter.Sort -> {
                filter.values.map { value ->
                    ContentTag(value, "$PREFIX_SORT$prefix${filter.name}/$value", source)
                }
            }
            is Filter.Text -> {
                listOf(ContentTag(
                    title = "📝 ${filter.name}",
                    key = "$PREFIX_TEXT$prefix${filter.name}",
                    source = source
                ))
            }
            is Filter.Group<*> -> {
                val state = filter.state
                val stateType = state?.javaClass?.name ?: "null"
                val stateSize = (state as? List<*>)?.size ?: "not-list"
                Log.d(TAG, "[mapFilterToTags] Group parent=$parentInfo name='${filter.name}' stateType=$stateType stateSize=$stateSize")
                val nestedTags = mutableListOf<ContentTag>()
                (filter.state as? List<*>)?.forEach { subItem ->
                    if (subItem is Filter<*>) {
                        val nestedPrefix = if (parentName != null) "$parentName/${filter.name}" else filter.name
                        nestedTags.addAll(mapFilterToTags(subItem, nestedPrefix, source))
                    }
                }
                Log.d(TAG, "[mapFilterToTags] Group result: ${nestedTags.size} nested tags")
                nestedTags
            }
            else -> {
                Log.d(TAG, "[mapFilterToTags] UNKNOWN filterType=$filterType parent=$parentInfo name='${filter.name}'")
                emptyList()
            }
        }
    }

    /**
     * Extract a readable title from a raw [Filter] value.
     * Some sources embed data-class representations like
     * {@code ThemeInfo(name=爱情, pathWord=xiaoyuan)} inside filter
     * values. Extract the first field value from those.
     * Fragments like {@code pathWord=aiqing)} (split by a buggy source)
     * are also discarded.
     */
    private fun Any?.cleanTitle(): String {
        if (this == null) return ""
        val raw = toString()
        // "ClassName(field1=value1, ...)" → extract first field value
        val classPattern = Regex("""^\w+\((\w+)=([^,)]+)""")
        val match = classPattern.find(raw)
        if (match != null) {
            val result = match.groupValues[2]
            Log.d(TAG, "[cleanTitle] class='${raw.take(60)}' -> '$result'")
            return result
        }
        // Fragment like "field=value)" without a class prefix → discard
        if (raw.matches(Regex("""^\w+=[^,)]+\)?$"""))) {
            Log.d(TAG, "[cleanTitle] fragment='${raw.take(60)}' -> EMPTY")
            return ""
        }
        Log.v(TAG, "[cleanTitle] plain='${raw.take(60)}' -> pass through")
        return raw
    }

    fun updateMihonFilters(mihonFilters: FilterList, kotoFilter: ContentListFilter) {
        val selectedTags = kotoFilter.tags.mapToSet { it.key }
        val excludedTags = kotoFilter.tagsExclude.mapToSet { it.key }

        mihonFilters.forEach { filter ->
            when (filter) {
                is Filter.Group<*> -> {
                    (filter.state as? List<*>)?.forEach { subItem ->
                        val sub = subItem as? Filter<*> ?: return@forEach
                        updateSingleFilter(sub, filter.name, selectedTags, excludedTags)
                    }
                }
                else -> {
                    updateSingleFilter(filter, null, selectedTags, excludedTags)
                }
            }
        }
    }

    private fun updateSingleFilter(filter: Filter<*>, parentName: String?, selectedTags: Set<String>, excludedTags: Set<String>) {
        val prefix = if (parentName != null) "$parentName/" else PREFIX_TOP
        when (filter) {
            is Filter.CheckBox -> {
                val key = "$prefix${filter.name}"
                filter.state = key in selectedTags
            }
            is Filter.TriState -> {
                val key = "$prefix${filter.name}"
                filter.state = when {
                    key in selectedTags -> Filter.TriState.STATE_INCLUDE
                    key in excludedTags -> Filter.TriState.STATE_EXCLUDE
                    else -> Filter.TriState.STATE_IGNORE
                }
            }
            is Filter.Select<*> -> {
                filter.values.forEachIndexed { index, value ->
                    val key = "$prefix${filter.name}/${value.cleanTitle()}"
                    if (key in selectedTags) {
                        filter.state = index
                    }
                }
            }
            is Filter.Sort -> {
                filter.values.forEachIndexed { index, value ->
                    val key = "$PREFIX_SORT$prefix${filter.name}/$value"
                    if (key in selectedTags) {
                        filter.state = Filter.Sort.Selection(index, filter.state?.ascending ?: false)
                    }
                }
            }
            is Filter.Text -> {
                val baseKey = "$PREFIX_TEXT$prefix${filter.name}"
                val matchingTag = selectedTags.find { it.startsWith(baseKey) }
                if (matchingTag != null) {
                    val value = if (matchingTag.contains("=")) {
                        matchingTag.substringAfter("=")
                    } else {
                        ""
                    }
                    filter.state = value
                }
            }
            is Filter.Group<*> -> {
                (filter.state as? List<*>)?.forEach { subItem ->
                    if (subItem is Filter<*>) {
                        val nestedPrefix = if (parentName != null) "$parentName/${filter.name}" else filter.name
                        updateSingleFilter(subItem, nestedPrefix, selectedTags, excludedTags)
                    }
                }
            }
            is Filter.Header, is Filter.Separator -> { }
            else -> {}
        }
    }
}

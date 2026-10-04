package org.skepsun.kototoro.mihon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.skepsun.kototoro.core.source.MihonFilterRules
import org.skepsun.kototoro.core.source.SourceFilterChange
import org.skepsun.kototoro.core.source.SourceFilterKind
import org.skepsun.kototoro.core.source.SourceFilterNode
import org.skepsun.kototoro.core.source.SourceFilterValue
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceTriState
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup

/** Android ABI adapter; tag/group/state rules are shared with the JVM source host. */
object MihonFilterMapper {
    fun mapOptions(mihonFilters: FilterList, source: ContentSource): ContentListFilterOptions {
        val projected = MihonFilterRules.options(Snapshot(mihonFilters).nodes,
            SourceRef(source.name, source.locale, source.contentType.name))
        fun tags(values: Set<org.skepsun.kototoro.core.source.SourceTag>) =
            values.mapTo(linkedSetOf()) { ContentTag(it.title, it.key, source) }
        return ContentListFilterOptions(
            availableTags = tags(projected.availableTags),
            tagGroups = projected.tagGroups.map { ContentTagGroup(it.title, tags(it.tags), it.isExclusive) },
        )
    }

    fun updateMihonFilters(mihonFilters: FilterList, kotoFilter: ContentListFilter) {
        val snapshot = Snapshot(mihonFilters)
        MihonFilterRules.changes(snapshot.nodes, kotoFilter.tags.map { it.key }.toSet(),
            kotoFilter.tagsExclude.map { it.key }.toSet()).forEach(snapshot::apply)
    }

    private class Snapshot(filters: FilterList) {
        private val native = linkedMapOf<String, Filter<*>>()
        val nodes = filters.mapIndexed { index, filter -> project(filter, listOf(index)) }

        private fun project(filter: Filter<*>, path: List<Int>): SourceFilterNode {
            val kind = when (filter) {
                is Filter.Header -> SourceFilterKind.HEADER
                is Filter.Separator -> SourceFilterKind.SEPARATOR
                is Filter.CheckBox -> SourceFilterKind.CHECKBOX
                is Filter.TriState -> SourceFilterKind.TRISTATE
                is Filter.Select<*> -> SourceFilterKind.SELECT
                is Filter.Sort -> SourceFilterKind.SORT
                is Filter.Text -> SourceFilterKind.TEXT
                is Filter.Group<*> -> SourceFilterKind.GROUP
                // External JARs can contain direct subclasses absent from this Android ABI's sealed declaration.
                else -> SourceFilterKind.UNSUPPORTED
            }
            val id = MihonFilterRules.nodeId(path, kind, filter.name)
            native[id] = filter
            val state = when (filter) {
                is Filter.CheckBox -> SourceFilterValue.Toggle(filter.state)
                is Filter.TriState -> SourceFilterValue.TriState(SourceTriState.entries[filter.state])
                is Filter.Select<*> -> SourceFilterValue.Choice(filter.state)
                is Filter.Sort -> filter.state?.let { SourceFilterValue.Sort(it.index, it.ascending) }
                is Filter.Text -> SourceFilterValue.Text(filter.state)
                else -> null
            }
            val values = when (filter) {
                is Filter.Select<*> -> filter.values.map { it?.toString().orEmpty() }
                is Filter.Sort -> filter.values.toList()
                else -> emptyList()
            }
            val children = if (filter is Filter.Group<*>) filter.state.mapIndexedNotNull { index, child ->
                (child as? Filter<*>)?.let { project(it, path + index) }
            } else emptyList()
            return SourceFilterNode(id, filter.name, kind, state, values, children)
        }

        @Suppress("UNCHECKED_CAST")
        fun apply(change: SourceFilterChange) {
            val value: Any? = when (val state = change.value) {
                is SourceFilterValue.Toggle -> state.value
                is SourceFilterValue.TriState -> state.value.ordinal
                is SourceFilterValue.Choice -> state.index
                is SourceFilterValue.Sort -> Filter.Sort.Selection(state.index, state.ascending)
                is SourceFilterValue.Text -> state.value
                null -> null
            }
            (native.getValue(change.id) as Filter<Any?>).state = value
        }
    }
}

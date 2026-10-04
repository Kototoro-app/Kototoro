package org.skepsun.kototoro.core.source

/** Pure projection of Mihon controls into the existing Kototoro tag keys and a full native control tree. */
object MihonFilterRules {
    fun nodeId(path: List<Int>, kind: SourceFilterKind, name: String): String =
        "${path.joinToString(".")}|${kind.name}|$name"

    fun options(nodes: List<SourceFilterNode>, source: SourceRef): SourceFilterOptions {
        val groups = mutableListOf<SourceTagGroup>()
        var header = "General"
        for (node in nodes) {
            when (node.kind) {
                SourceFilterKind.HEADER -> header = node.name
                SourceFilterKind.SEPARATOR -> Unit
                SourceFilterKind.GROUP -> {
                    val checkboxes = linkedSetOf<SourceTag>()
                    for (child in node.children) {
                        val tags = tags(child, node.name, source).toSet()
                        if (child.kind == SourceFilterKind.SELECT || child.kind == SourceFilterKind.SORT) {
                            if (tags.isNotEmpty()) groups += SourceTagGroup("${node.name} - ${child.name}", tags)
                        } else {
                            checkboxes += tags
                        }
                    }
                    if (checkboxes.isNotEmpty()) groups += SourceTagGroup(node.name, checkboxes)
                }
                else -> tags(node, null, source).toSet().takeIf(Set<SourceTag>::isNotEmpty)?.let {
                    groups += SourceTagGroup(header, it)
                }
            }
        }
        val merged = groups.groupBy { it.title }.map { (title, items) ->
            SourceTagGroup(title, items.flatMap { it.tags }.toSet())
        }
        return SourceFilterOptions(
            merged.flatMap { it.tags }.toSet(), merged, emptySet(), emptySet(), emptySet(), emptySet(), emptySet(),
        )
    }

    /** Returns only state writes; adapters keep native subclass instances and any opaque choice objects. */
    fun changes(
        nodes: List<SourceFilterNode>,
        selected: Set<String>,
        excluded: Set<String>,
        dynamic: List<SourceFilterChange> = emptyList(),
        strict: Boolean = false,
        applyLegacy: Boolean = true,
    ): List<SourceFilterChange> {
        val result = linkedMapOf<String, SourceFilterChange>()
        val consumedSelected = mutableSetOf<String>()
        val consumedExcluded = mutableSetOf<String>()
        fun walk(node: SourceFilterNode, parent: String?) {
            val prefix = parent?.let { "$it/" } ?: "top:"
            val key = "$prefix${node.name}"
            fun remember(value: SourceFilterValue?) { result[node.id] = SourceFilterChange(node.id, value) }
            when (node.kind) {
                SourceFilterKind.CHECKBOX -> {
                    if (key in selected) consumedSelected += key
                    remember(SourceFilterValue.Toggle(key in selected))
                }
                SourceFilterKind.TRISTATE -> {
                    if (key in selected) consumedSelected += key
                    if (key in excluded) consumedExcluded += key
                    remember(SourceFilterValue.TriState(when {
                        key in selected -> SourceTriState.INCLUDE
                        key in excluded -> SourceTriState.EXCLUDE
                        else -> SourceTriState.IGNORE
                    }))
                }
                SourceFilterKind.SELECT, SourceFilterKind.SORT -> {
                    node.values.forEachIndexed { index, raw ->
                        val choiceKey = if (node.kind == SourceFilterKind.SORT) "sort:$key/$raw"
                        else "$key/${MihonModelRules.cleanGenre(raw)}"
                        if (choiceKey in selected) {
                            consumedSelected += choiceKey
                            if (node.kind == SourceFilterKind.SORT) {
                                val ascending = (node.state as? SourceFilterValue.Sort)?.ascending ?: false
                                remember(SourceFilterValue.Sort(index, ascending))
                            } else {
                                remember(SourceFilterValue.Choice(index))
                            }
                        }
                    }
                }
                SourceFilterKind.TEXT -> {
                    val base = "text:$key"
                    // A control named Author must not accidentally consume AuthorExtra's tag.
                    selected.firstOrNull { it == base || it.startsWith("$base=") }?.let {
                        consumedSelected += it
                        remember(SourceFilterValue.Text(it.substringAfter('=', "")))
                    }
                }
                SourceFilterKind.GROUP -> node.children.forEach { child ->
                    walk(child, if (parent == null) node.name else "$parent/${node.name}")
                }
                else -> Unit
            }
        }
        if (applyLegacy) nodes.forEach { walk(it, null) }
        if (strict && (consumedSelected != selected || consumedExcluded != excluded ||
                selected.any { it in excluded })) {
            throw SourceInvalidArgumentException()
        }
        val flattened = flatten(nodes)
        val byId = flattened.associateBy { it.id }
        if (byId.size != flattened.size || dynamic.map { it.id }.distinct().size != dynamic.size) {
            throw SourceInvalidArgumentException()
        }
        for (change in dynamic) {
            val node = byId[change.id] ?: throw SourceInvalidArgumentException()
            val valid = when (val value = change.value) {
                is SourceFilterValue.Toggle -> node.kind == SourceFilterKind.CHECKBOX
                is SourceFilterValue.TriState -> node.kind == SourceFilterKind.TRISTATE
                is SourceFilterValue.Choice ->
                    node.kind == SourceFilterKind.SELECT && value.index in node.values.indices
                is SourceFilterValue.Sort -> node.kind == SourceFilterKind.SORT && value.index in node.values.indices
                is SourceFilterValue.Text -> node.kind == SourceFilterKind.TEXT
                null -> node.kind == SourceFilterKind.SORT
            }
            if (!valid) throw SourceInvalidArgumentException()
            result[change.id] = change
        }
        return result.values.toList()
    }

    fun flatten(nodes: List<SourceFilterNode>): List<SourceFilterNode> =
        nodes.flatMap { listOf(it) + flatten(it.children) }

    private fun tags(node: SourceFilterNode, parent: String?, source: SourceRef): List<SourceTag> {
        val prefix = parent?.let { "$it/" } ?: "top:"
        return when (node.kind) {
            SourceFilterKind.CHECKBOX, SourceFilterKind.TRISTATE ->
                listOf(SourceTag(MihonModelRules.cleanGenre(node.name), "$prefix${node.name}", source))
            SourceFilterKind.SELECT -> node.values.mapNotNull { value ->
                MihonModelRules.cleanGenre(value).takeIf(String::isNotEmpty)?.let {
                    SourceTag(it, "$prefix${node.name}/$it", source)
                }
            }
            SourceFilterKind.SORT -> node.values.map { SourceTag(it, "sort:$prefix${node.name}/$it", source) }
            SourceFilterKind.TEXT -> listOf(SourceTag("📝 ${node.name}", "text:$prefix${node.name}", source))
            SourceFilterKind.GROUP -> node.children.flatMap {
                tags(it, if (parent == null) node.name else "$parent/${node.name}", source)
            }
            else -> emptyList()
        }
    }
}

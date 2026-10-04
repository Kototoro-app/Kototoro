package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.MihonFilterRules
import org.skepsun.kototoro.core.source.SourceDynamicFilters
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterKind
import org.skepsun.kototoro.core.source.SourceFilterNode
import org.skepsun.kototoro.core.source.SourceFilterValue
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceTriState
import java.util.IdentityHashMap

/** Where an ABI keeps its model classes; the manga and anime filter hierarchies are shaped the same way. */
internal enum class NativeAbi(val modelPackage: String, val filterClass: String) {
    MIHON("eu.kanade.tachiyomi.source.model", "Filter"),
    ANIYOMI("eu.kanade.tachiyomi.animesource.model", "AnimeFilter"),
}

/** Keeps native filter subclasses/choice objects in the JVM and projects only portable values. */
internal class MihonNativeFilters(private val lease: MihonJarRegistry.SourceLease, private val abi: NativeAbi = NativeAbi.MIHON) {
    val nativeList: Any = requireNotNull(MihonReflection.call(lease.instance, "getFilterList"))
    private val nativeNodes = linkedMapOf<String, Any>()
    private val originalStates = linkedMapOf<String, Any?>()
    private val active = IdentityHashMap<Any, Boolean>()
    private val written = mutableListOf<String>()
    val definition: SourceDynamicFilters

    init {
        val list = nativeList as? List<*> ?: MihonReflection.call(nativeList, "getList") as List<*>
        definition = SourceDynamicFilters(lease.descriptor.source, list.mapIndexed { index, value ->
            project(requireNotNull(value), listOf(index))
        })
    }

    fun apply(filter: SourceFilter) {
        if ((filter.tags + filter.tagsExclude).any { it.source.name != lease.descriptor.source.name }) {
            throw SourceInvalidArgumentException()
        }
        val changes = MihonFilterRules.changes(definition.nodes, filter.tags.map { it.key }.toSet(),
            filter.tagsExclude.map { it.key }.toSet(), filter.dynamicFilters, strict = true,
            applyLegacy = filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty())
        try {
            for (change in changes) {
                val value = when (val state = change.value) {
                    is SourceFilterValue.Toggle -> state.value
                    is SourceFilterValue.TriState -> state.value.ordinal
                    is SourceFilterValue.Choice -> state.index
                    is SourceFilterValue.Text -> state.value
                    is SourceFilterValue.Sort -> MihonReflection.createIn(
                        lease.loader, abi.modelPackage, "${abi.filterClass}\$Sort\$Selection", state.index, state.ascending,
                    )
                    null -> null
                }
                written += change.id
                MihonReflection.call(nativeNodes.getValue(change.id), "setState", value)
            }
        } catch (error: Throwable) {
            try { restore() } catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            throw error
        }
    }

    fun restore() {
        val failures = mutableListOf<Throwable>()
        for (id in written.asReversed()) {
            try { MihonReflection.call(nativeNodes.getValue(id), "setState", originalStates[id]) }
            catch (error: Throwable) { failures += error }
        }
        written.clear()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun project(filter: Any, path: List<Int>): SourceFilterNode {
        require(path.size <= 32 && nativeNodes.size < 10000) { "Filter tree limit" }
        check(active.put(filter, true) == null) { "Cyclic filter tree" }
        try {
            val name = MihonReflection.call(filter, "getName") as String
            val kind = kind(filter)
            val id = MihonFilterRules.nodeId(path, kind, name)
            val state = MihonReflection.call(filter, "getState")
            nativeNodes[id] = filter
            originalStates[id] = state
            val values = if (kind == SourceFilterKind.SELECT || kind == SourceFilterKind.SORT) {
                (MihonReflection.call(filter, "getValues") as Array<*>).map { it?.toString().orEmpty() }
            } else emptyList()
            val projected = when (kind) {
                SourceFilterKind.CHECKBOX -> SourceFilterValue.Toggle(state as Boolean)
                SourceFilterKind.TRISTATE ->
                    SourceFilterValue.TriState(SourceTriState.entries[(state as Number).toInt()])
                SourceFilterKind.SELECT -> SourceFilterValue.Choice((state as Number).toInt())
                SourceFilterKind.TEXT -> SourceFilterValue.Text(state as String)
                SourceFilterKind.SORT -> state?.let {
                    SourceFilterValue.Sort(MihonReflection.call(it, "getIndex") as Int,
                        MihonReflection.call(it, "getAscending") as Boolean)
                }
                else -> null
            }
            val children = if (kind == SourceFilterKind.GROUP) {
                (state as List<*>).mapIndexedNotNull { index, child ->
                    if (child == null || !isFilter(child)) null else project(child, path + index)
                }
            } else emptyList()
            return SourceFilterNode(id, name, kind, projected, values, children)
        } finally {
            active.remove(filter)
        }
    }

    private fun isFilter(value: Any) = Class.forName(
        "${abi.modelPackage}.${abi.filterClass}", false, lease.loader,
    ).isInstance(value)

    private fun kind(filter: Any): SourceFilterKind {
        var type: Class<*>? = filter.javaClass
        val names = mapOf("Header" to SourceFilterKind.HEADER, "Separator" to SourceFilterKind.SEPARATOR,
            "CheckBox" to SourceFilterKind.CHECKBOX, "TriState" to SourceFilterKind.TRISTATE,
            "Select" to SourceFilterKind.SELECT, "Sort" to SourceFilterKind.SORT, "Text" to SourceFilterKind.TEXT,
            "Group" to SourceFilterKind.GROUP)
        while (type != null) {
            val nativeName = type.name.removePrefix("${abi.modelPackage}.${abi.filterClass}\$")
            if (nativeName != type.name) names[nativeName]?.let { return it }
            type = type.superclass
        }
        return SourceFilterKind.UNSUPPORTED
    }
}

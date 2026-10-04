package org.skepsun.kototoro.core.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceFilterKind { HEADER, SEPARATOR, CHECKBOX, TRISTATE, SELECT, SORT, TEXT, GROUP, UNSUPPORTED }

@Serializable
enum class SourceTriState { IGNORE, INCLUDE, EXCLUDE }

/** Typed values preserve native indexes and sort direction; display labels are never used as native values. */
@Serializable
sealed interface SourceFilterValue {
    @Serializable @SerialName("toggle") data class Toggle(val value: Boolean) : SourceFilterValue
    @Serializable @SerialName("triState") data class TriState(val value: SourceTriState) : SourceFilterValue
    @Serializable @SerialName("choice") data class Choice(val index: Int) : SourceFilterValue
    @Serializable @SerialName("sort") data class Sort(val index: Int, val ascending: Boolean) : SourceFilterValue
    @Serializable @SerialName("text") data class Text(val value: String) : SourceFilterValue
}

@Serializable
data class SourceFilterNode(
    val id: String,
    val name: String,
    val kind: SourceFilterKind,
    val state: SourceFilterValue? = null,
    val values: List<String> = emptyList(),
    val children: List<SourceFilterNode> = emptyList(),
)

@Serializable
data class SourceDynamicFilters(val source: SourceRef, val nodes: List<SourceFilterNode>)

/** Null clears a nullable native Sort selection; other controls require their corresponding typed value. */
@Serializable
data class SourceFilterChange(val id: String, val value: SourceFilterValue?)

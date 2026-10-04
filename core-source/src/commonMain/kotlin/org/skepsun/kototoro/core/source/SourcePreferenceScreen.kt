package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable

@Serializable
enum class SourcePreferenceKind { INFO, TEXT, TOGGLE, CHOICE, MULTI_CHOICE, GROUP, UNSUPPORTED }

@Serializable
data class SourcePreferenceChoice(val title: String, val value: String)

@Serializable
data class SourcePreferenceNode(
    val id: String,
    val key: String?,
    val title: String,
    val summary: String,
    val kind: SourcePreferenceKind,
    val enabled: Boolean,
    val visible: Boolean,
    val value: SourcePreferenceValue? = null,
    val defaultValue: SourcePreferenceValue? = null,
    val choices: List<SourcePreferenceChoice> = emptyList(),
    val children: List<SourcePreferenceNode> = emptyList(),
)

/** Revision identifies live native control objects, not merely a hash of their displayed labels. */
@Serializable
data class SourcePreferenceScreen(val source: SourceRef, val revision: String, val nodes: List<SourcePreferenceNode>)

@Serializable
enum class SourcePreferenceUpdateStatus { ACCEPTED, REJECTED, PERSISTENCE_FAILED }

@Serializable
data class SourcePreferenceUpdate(val status: SourcePreferenceUpdateStatus, val screen: SourcePreferenceScreen)

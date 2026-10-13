package org.skepsun.kototoro.settings.sources.groups

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SOURCE_GROUP_SCHEMA_VERSION = 1
const val SOURCE_GROUP_MAX_BYTES = 2L * 1024L * 1024L

internal val SOURCE_GROUP_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

@Serializable
data class SourceGroup(
	val schema: Int = SOURCE_GROUP_SCHEMA_VERSION,
	val id: String,
	val name: String,
	val createdAt: Long,
	val repos: List<SourceGroupRepo> = emptyList(),
	val packages: List<SourceGroupPackage> = emptyList(),
	val sourceStates: List<SourceGroupSourceState> = emptyList(),
	val jarPriority: List<String> = emptyList(),
)

@Serializable
data class SourceGroupRepo(
	val type: String,
	val url: String,
	val name: String,
)

@Serializable
data class SourceGroupPackage(
	val pkg: String,
	val kind: String,
	val versionName: String? = null,
	val versionCode: Long = 0L,
	val repoUrl: String? = null,
)

@Serializable
data class SourceGroupSourceState(
	val source: String,
	val enabled: Boolean,
	val pinned: Boolean,
	val sortKey: Int,
	/** Locked enabled state (true = locked on, false = locked off); null when not locked. */
	val locked: Boolean? = null,
)

object SourceGroupJson {

	val json = Json {
		prettyPrint = true
		encodeDefaults = true
		ignoreUnknownKeys = true
	}

	fun encode(group: SourceGroup): String = json.encodeToString(SourceGroup.serializer(), group)

	fun decode(text: String): SourceGroup = json.decodeFromString(SourceGroup.serializer(), text)
}

/** Returns true when the group can be safely stored and applied. */
fun SourceGroup.isValid(): Boolean {
	return schema == SOURCE_GROUP_SCHEMA_VERSION && SOURCE_GROUP_ID_REGEX.matches(id)
}

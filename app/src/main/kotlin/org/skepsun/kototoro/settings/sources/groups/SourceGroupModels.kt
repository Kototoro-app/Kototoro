package org.skepsun.kototoro.settings.sources.groups

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val SOURCE_GROUP_SCHEMA_VERSION = 1
const val SOURCE_GROUP_MAX_BYTES = 2L * 1024L * 1024L

/** [SourceGroupPackage.installMode] for packages installed through the Android package installer. */
const val SOURCE_GROUP_INSTALL_MODE_SYSTEM = "system"

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
	/** [SOURCE_GROUP_INSTALL_MODE_SYSTEM] for normal APK installs; null means a managed (sideloaded) package. */
	val installMode: String? = null,
)

@Serializable
data class SourceGroupSourceState(
	val source: String,
	val enabled: Boolean,
	val pinned: Boolean,
	val sortKey: Int,
	/** Locked enabled state (true = locked on, false = locked off); null when not locked. */
	val locked: Boolean? = null,
	/** Manual domain override saved in the source settings; null when none was set. */
	val domain: String? = null,
)

object SourceGroupJson {

	val json = Json {
		prettyPrint = true
		encodeDefaults = true
		ignoreUnknownKeys = true
	}

	fun encode(group: SourceGroup): String = json.encodeToString(SourceGroup.serializer(), group)

	fun decode(text: String): SourceGroup = json.decodeFromString(SourceGroup.serializer(), text)

	/** Reads only the "schema" field, so a newer file whose body no longer decodes is still recognized. */
	fun peekSchema(text: String): Int? = runCatching {
		json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.intOrNull
	}.getOrNull()
}

/** True when the group was written by a newer app version than this one understands. */
fun SourceGroup.isNewerSchema(): Boolean = schema > SOURCE_GROUP_SCHEMA_VERSION

/** Returns true when the group can be safely stored and applied. */
fun SourceGroup.isValid(): Boolean {
	return schema == SOURCE_GROUP_SCHEMA_VERSION && SOURCE_GROUP_ID_REGEX.matches(id)
}

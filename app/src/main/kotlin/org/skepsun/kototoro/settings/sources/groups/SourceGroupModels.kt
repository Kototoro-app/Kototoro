package org.skepsun.kototoro.settings.sources.groups

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val SOURCE_GROUP_SCHEMA_VERSION = 2
const val SOURCE_GROUP_MAX_BYTES = 2L * 1024L * 1024L

/** [SourceGroupPackage.installMode] for packages installed through the Android package installer. */
const val SOURCE_GROUP_INSTALL_MODE_SYSTEM = "system"

internal val SOURCE_GROUP_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

@Serializable(with = SourceGroupSerializer::class)
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

/** On-disk and backup shape of [SourceGroup]: plain enabled sources are stored by name only. */
@Serializable
internal class SourceGroupWire(
	val schema: Int = 1,
	val id: String,
	val name: String,
	val createdAt: Long,
	val repos: List<SourceGroupRepo> = emptyList(),
	val packages: List<SourceGroupPackage> = emptyList(),
	val sourceStates: List<SourceGroupSourceState> = emptyList(),
	/** Plain enabled sources (unpinned, unlocked, no domain) in ascending sortKey order. */
	val enabledSources: List<String> = emptyList(),
	/** sortKey of the first [enabledSources] entry. */
	val enabledSortStart: Int = 0,
	/** Flattened (index, delta) pairs for entries whose sortKey differs from the previous one by more than 1. */
	val enabledSortGaps: List<Int> = emptyList(),
	/** Plain enabled sources with sortKey 0. */
	val enabledUnsorted: List<String> = emptyList(),
	val jarPriority: List<String> = emptyList(),
)

object SourceGroupSerializer : KSerializer<SourceGroup> {

	override val descriptor: SerialDescriptor = SourceGroupWire.serializer().descriptor

	override fun serialize(encoder: Encoder, value: SourceGroup) {
		encoder.encodeSerializableValue(SourceGroupWire.serializer(), value.toWire())
	}

	override fun deserialize(decoder: Decoder): SourceGroup =
		decoder.decodeSerializableValue(SourceGroupWire.serializer()).toGroup()
}

private fun SourceGroupSourceState.isPlain(): Boolean =
	enabled && !pinned && locked == null && domain == null

private fun SourceGroup.toWire(): SourceGroupWire {
	val detailed = ArrayList<SourceGroupSourceState>()
	val unsorted = ArrayList<String>()
	val candidates = ArrayList<SourceGroupSourceState>()
	for (state in sourceStates) {
		when {
			!state.isPlain() -> detailed += state
			state.sortKey == 0 -> unsorted += state.source
			else -> candidates += state
		}
	}
	candidates.sortBy { it.sortKey }
	val kept = ArrayList<SourceGroupSourceState>()
	var last = Int.MIN_VALUE
	for (state in candidates) {
		// A repeated sortKey cannot be rebuilt from the order alone, so that entry keeps its full record.
		if (state.sortKey > last) {
			kept += state
			last = state.sortKey
		} else {
			detailed += state
		}
	}
	val gaps = ArrayList<Int>()
	for (i in 1 until kept.size) {
		val delta = kept[i].sortKey - kept[i - 1].sortKey
		if (delta != 1) {
			gaps += i
			gaps += delta
		}
	}
	val compact = kept.isNotEmpty() || unsorted.isNotEmpty()
	return SourceGroupWire(
		schema = if (compact) maxOf(schema, 2) else schema,
		id = id,
		name = name,
		createdAt = createdAt,
		repos = repos,
		packages = packages,
		sourceStates = detailed,
		enabledSources = kept.map { it.source },
		enabledSortStart = kept.firstOrNull()?.sortKey ?: 0,
		enabledSortGaps = gaps,
		enabledUnsorted = unsorted,
		jarPriority = jarPriority,
	)
}

private fun SourceGroupWire.toGroup(): SourceGroup {
	if (enabledSortGaps.size % 2 != 0) throw SerializationException("Invalid enabledSortGaps")
	val gapAt = HashMap<Int, Int>()
	for (i in enabledSortGaps.indices step 2) {
		val index = enabledSortGaps[i]
		val delta = enabledSortGaps[i + 1]
		if (index !in 1 until enabledSources.size || delta <= 0) throw SerializationException("Invalid enabledSortGaps")
		gapAt[index] = delta
	}
	val states = ArrayList<SourceGroupSourceState>(sourceStates.size + enabledSources.size + enabledUnsorted.size)
	states += sourceStates
	var key = enabledSortStart
	enabledSources.forEachIndexed { index, source ->
		if (index > 0) key += gapAt[index] ?: 1
		states += SourceGroupSourceState(source = source, enabled = true, pinned = false, sortKey = key)
	}
	enabledUnsorted.forEach { source ->
		states += SourceGroupSourceState(source = source, enabled = true, pinned = false, sortKey = 0)
	}
	return SourceGroup(
		schema = schema,
		id = id,
		name = name,
		createdAt = createdAt,
		repos = repos,
		packages = packages,
		sourceStates = states,
		jarPriority = jarPriority,
	)
}

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
	return schema in 1..SOURCE_GROUP_SCHEMA_VERSION && SOURCE_GROUP_ID_REGEX.matches(id)
}

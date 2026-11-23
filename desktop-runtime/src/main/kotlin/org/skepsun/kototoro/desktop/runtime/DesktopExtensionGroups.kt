package org.skepsun.kototoro.desktop.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One package an extension group names: [pkg] is the identity, [repoUrl] where Android got it from ([kind],
 * [versionName] and [versionCode] only label it).
 */
data class DesktopExtensionPackage(
    val pkg: String,
    val repoUrl: String? = null,
    val kind: String = "mihon",
    val versionName: String? = null,
    val versionCode: Long = 0L,
)

/** One repository an extension group names, in Android's `repos` shape. */
data class DesktopExtensionRepo(val type: String, val url: String, val name: String)

/**
 * What this host writes as its extension group: the installed packages, their repositories, and the organization the
 * backup has to carry — the enabled sources in the order the rail shows them, the sources the user switched off and
 * Android's JAR priority.
 */
data class DesktopExtensionExport(
    val id: String,
    val name: String,
    val createdAt: Long,
    val repos: List<DesktopExtensionRepo> = emptyList(),
    val packages: List<DesktopExtensionPackage> = emptyList(),
    val enabledSources: List<String> = emptyList(),
    val disabledSources: List<String> = emptyList(),
    val jarPriority: List<String> = emptyList(),
)

/** What the extension groups of a backup ask a restore to do, already merged over every group the archive holds. */
data class DesktopExtensionRestore(
    val packages: List<DesktopExtensionPackage> = emptyList(),
    val repositoryUrls: List<String> = emptyList(),
    /** Enabled sources in the backed-up order; sources any group switched off are left out. */
    val enabledOrder: List<String> = emptyList(),
    val disabledSources: List<String> = emptyList(),
    val jarPriority: List<String> = emptyList(),
)

/**
 * Android's extension group wire format (`EXTENSION_GROUPS`, schema 2, the one `SourceGroupSerializer` writes): the
 * packages a restore installs, the repositories they come from, and the source organization. Enabled sources go out as
 * the compact `enabledSources` sequence — `enabledSortStart` plus `enabledSortGaps` rebuild their `sortKey`s — while
 * sources the user switched off keep a full `sourceStates` record. Reading is deliberately forgiving: an archive from
 * an older version that has no section, no `enabledSort*` fields or no `sourceStates` at all still decodes, with the
 * missing fields taken as "nothing to restore" instead of an error.
 */
object DesktopExtensionGroups {

    /** The `schema` this host writes; Android's `SOURCE_GROUP_SCHEMA_VERSION`. */
    const val SCHEMA = 2

    fun encode(group: DesktopExtensionExport): String {
        val json = buildJsonObject {
            put("schema", SCHEMA)
            put("id", group.id)
            put("name", group.name)
            put("createdAt", group.createdAt)
            putJsonArray("repos") {
                group.repos.forEach { repository ->
                    add(buildJsonObject {
                        put("type", repository.type)
                        put("url", repository.url)
                        put("name", repository.name)
                    })
                }
            }
            putJsonArray("packages") {
                group.packages.forEach { pkg ->
                    add(buildJsonObject {
                        put("pkg", pkg.pkg)
                        put("kind", pkg.kind)
                        pkg.versionName?.let { put("versionName", it) }
                        put("versionCode", pkg.versionCode)
                        put("installMode", "managed")
                    })
                }
            }
            putJsonArray("sourceStates") {
                group.disabledSources.forEach { source ->
                    add(buildJsonObject {
                        put("source", source)
                        put("enabled", false)
                        put("pinned", false)
                        put("sortKey", 0)
                    })
                }
            }
            // One flat sequence needs no gaps: every entry follows the previous one, and none is unsorted.
            putJsonArray("enabledSources") { group.enabledSources.forEach { add(JsonPrimitive(it)) } }
            put("enabledSortStart", 1)
            put("enabledSortGaps", JsonArray(emptyList()))
            put("enabledUnsorted", JsonArray(emptyList()))
            putJsonArray("jarPriority") { group.jarPriority.forEach { add(JsonPrimitive(it)) } }
        }
        return JsonArray(listOf(json)).toString()
    }

    fun decode(groups: List<JsonObject>): DesktopExtensionRestore {
        val packages = LinkedHashMap<String, DesktopExtensionPackage>()
        val repositoryUrls = LinkedHashSet<String>()
        val disabled = LinkedHashSet<String>()
        val enabled = ArrayList<Pair<String, Int>>()
        var jarPriority = emptyList<String>()
        for (group in groups) {
            for (pkg in group.packages()) packages.putIfAbsent(pkg.pkg, pkg)
            repositoryUrls += group.repositoryUrls()
            disabled += group.disabledSources()
            enabled += group.orderedEnabled()
            if (jarPriority.isEmpty()) jarPriority = group.jarPriority()
        }
        val enabledOrder = enabled
            // `sortKey` 0 means "unsorted"; merged after the ordered sources, and stable within the same key.
            .sortedBy { (_, sortKey) -> if (sortKey == 0) Int.MAX_VALUE else sortKey }
            .map { (source, _) -> source }
            .filterNot { it in disabled }
            .distinct()
        return DesktopExtensionRestore(
            packages = packages.values.toList(),
            repositoryUrls = repositoryUrls.toList(),
            enabledOrder = enabledOrder,
            disabledSources = disabled.toList(),
            jarPriority = jarPriority,
        )
    }

    /**
     * Every enabled source the group names, with the `sortKey` that places it: the detailed `sourceStates` records plus
     * the compact `enabledSources`/`enabledSortStart`/`enabledSortGaps`/`enabledUnsorted` form. A malformed gap list is
     * treated as "no gaps" rather than failing the restore.
     */
    private fun JsonObject.orderedEnabled(): List<Pair<String, Int>> {
        val states = array("sourceStates").objects()
        val result = ArrayList<Pair<String, Int>>(states.size)
        for (state in states) {
            val source = state.string("source")?.takeIf { it.isNotBlank() } ?: continue
            if (state.boolean("enabled") != true) continue
            result += source to (state.integer("sortKey") ?: 0)
        }
        val names = array("enabledSources").strings()
        if (names.isNotEmpty()) {
            val gapAt = HashMap<Int, Int>()
            val gaps = array("enabledSortGaps").ints()
            if (gaps != null && gaps.size % 2 == 0) {
                for (index in gaps.indices step 2) {
                    val at = gaps[index]
                    val delta = gaps[index + 1]
                    if (at in 1 until names.size && delta > 0) gapAt[at] = delta
                }
            }
            var sortKey = integer("enabledSortStart") ?: 0
            names.forEachIndexed { index, source ->
                if (index > 0) sortKey += gapAt[index] ?: 1
                result += source to sortKey
            }
        }
        for (source in array("enabledUnsorted").strings()) result += source to 0
        return result
    }

    private fun JsonObject.packages(): List<DesktopExtensionPackage> =
        array("packages").objects().mapNotNull { item ->
            val pkg = item.string("pkg")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DesktopExtensionPackage(
                pkg = pkg,
                repoUrl = item.string("repoUrl")?.takeIf { it.isNotBlank() },
                kind = item.string("kind")?.takeIf { it.isNotBlank() } ?: "mihon",
                versionName = item.string("versionName"),
                versionCode = item.long("versionCode") ?: 0L,
            )
        }

    private fun JsonObject.repositoryUrls(): List<String> =
        array("repos").objects().mapNotNull { it.string("url")?.takeIf(String::isNotBlank) }

    /** Sources the group records as switched off; the compact `enabledSources` form only ever lists enabled ones. */
    private fun JsonObject.disabledSources(): List<String> =
        array("sourceStates").objects().mapNotNull { state ->
            val source = state.string("source")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (state.boolean("enabled") == false) source else null
        }

    private fun JsonObject.jarPriority(): List<String> = array("jarPriority").strings()

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray?.objects(): List<JsonObject> =
        this.orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonArray?.strings(): List<String> =
        this.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

    /** The flattened numbers of `enabledSortGaps`, or null when any entry is not a number. */
    private fun JsonArray?.ints(): List<Int>? {
        val values = ArrayList<Int>(this?.size ?: 0)
        for (element in this.orEmpty()) values += (element as? JsonPrimitive)?.intOrNull ?: return null
        return values
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()

    private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
}

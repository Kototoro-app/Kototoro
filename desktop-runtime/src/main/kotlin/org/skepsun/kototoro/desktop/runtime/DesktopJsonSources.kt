package org.skepsun.kototoro.desktop.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * One imported TVBox / Apple CMS site. [config] is Android's own `json_sources` document
 * (`schemaVersion/importType/site/root/meta`), so the same stored text serves both platforms and the site's
 * identity ([id], `JSON_TVBOX_<hash>`) comes from the shared import rules.
 */
@Serializable
data class DesktopJsonSource(
    val id: String,
    val name: String,
    val config: String,
    val iconUrl: String? = null,
    /** The CMS API the config points at, shown on the row; `id` stands in when a document omits it. */
    val api: String? = null,
)

/**
 * Android keeps imported JSON sources in a database table; the Windows host stores the same documents in one
 * preference value. Spider sites never reach this list — the shared importer skips them.
 */
class DesktopJsonSources(private val store: SourcePreferences) {
    val errors = mutableListOf<String>()

    fun all(): List<DesktopJsonSource> {
        val raw = savedText()
        if (raw.isBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<DesktopJsonSource>>(raw) }.getOrElse { error ->
            errors += error.message ?: error.javaClass.simpleName
            emptyList()
        }
    }

    /** Adds sites, replacing earlier copies of the same id so a re-import refreshes the stored document. */
    fun add(sources: List<DesktopJsonSource>): Boolean {
        if (sources.isEmpty()) return true
        val merged = LinkedHashMap<String, DesktopJsonSource>()
        all().forEach { merged[it.id] = it }
        sources.forEach { merged[it.id] = it }
        return save(merged.values.toList())
    }

    fun remove(id: String): Boolean = removeAll(setOf(id))

    fun removeAll(ids: Set<String>): Boolean = save(all().filterNot { it.id in ids })

    private fun save(sources: List<DesktopJsonSource>): Boolean = store.edit(
        SourcePreferenceEdit(changes = mapOf(KEY to SourcePreferenceValue.Text(Json.encodeToString(sources)))),
    )

    private fun savedText(): String = (store.snapshot()[KEY] as? SourcePreferenceValue.Text)?.value.orEmpty()

    companion object {
        const val KEY = "json_sources"
    }
}

package org.skepsun.kototoro.desktop.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.model.jsonsource.LegadoSourceCatalog
import org.skepsun.kototoro.desktop.runtime.DesktopJsonSource

internal data class DesktopLegadoImport(val sources: List<DesktopJsonSource>, val skipped: Int)

internal object DesktopLegadoCatalog {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null means this is not a Legado catalog, so the caller may try its other JSON importers. */
    fun read(content: String): DesktopLegadoImport? {
        // Legado shares a catalog as an array and a single book source as a bare object.
        val array = when (val root = runCatching { json.parseToJsonElement(content) }.getOrNull()) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> return null
        }
        if (array.none { it is JsonObject && ("bookSourceUrl" in it || "bookSourceName" in it) }) return null
        val sources = array.mapNotNull { item ->
            val config = runCatching { json.decodeFromJsonElement<LegadoBookSource>(item) }.getOrNull()
                ?: return@mapNotNull null
            if (config.bookSourceType != 0 || config.bookSourceName.isBlank() ||
                !(config.bookSourceUrl.startsWith("http://") || config.bookSourceUrl.startsWith("https://"))) {
                return@mapNotNull null
            }
            DesktopJsonSource(
                id = LegadoSourceCatalog.sourceId(config.bookSourceUrl, config.bookSourceType),
                name = config.bookSourceName,
                config = json.encodeToString(config),
                api = config.bookSourceUrl,
            )
        }
        return DesktopLegadoImport(sources, array.size - sources.size)
    }

    fun source(stored: DesktopJsonSource): DesktopLegadoSource? {
        if (!DesktopLegadoSourceRuntime.owns(stored.id)) return null
        return runCatching { json.decodeFromString<LegadoBookSource>(stored.config) }.getOrNull()
            ?.takeIf { it.bookSourceType == 0 }
            ?.let { DesktopLegadoSource(stored.id, it) }
    }
}

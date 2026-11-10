package org.skepsun.kototoro.core.model.jsonsource

import kotlinx.serialization.json.Json

/** Shared Legado catalog decoding and source identities for Android and desktop. */
object LegadoSourceCatalog {

    fun decode(content: String, json: Json): List<LegadoBookSource> = json.decodeFromString(content)

    fun sourceId(url: String, bookSourceType: Int): String {
        val prefix = if (bookSourceType == 2) "JSON_LEGADO_M_" else "JSON_LEGADO_"
        return prefix + url.hashCode().toUInt().toString(16).uppercase()
    }
}

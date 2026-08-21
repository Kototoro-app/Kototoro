package org.skepsun.kototoro.core.jsonsource

import org.json.JSONObject

data class JsonSourceImportMetadata(
    val sourceLocator: String?,
    val sourceTitle: String?,
    val importKind: String?,
) {
    companion object {
        private const val KEY_META = "_kototoroMeta"
        private const val KEY_SOURCE_LOCATOR = "sourceLocator"
        private const val KEY_SOURCE_TITLE = "sourceTitle"
        private const val KEY_IMPORT_KIND = "importKind"

        fun parse(rawConfig: String): JsonSourceImportMetadata? {
            return runCatching {
                val meta = JSONObject(rawConfig).optJSONObject(KEY_META) ?: return@runCatching null
                JsonSourceImportMetadata(
                    sourceLocator = meta.optStringOrNull(KEY_SOURCE_LOCATOR),
                    sourceTitle = meta.optStringOrNull(KEY_SOURCE_TITLE),
                    importKind = meta.optStringOrNull(KEY_IMPORT_KIND),
                )
            }.getOrNull()
        }

        fun attach(
            rawConfig: String,
            sourceLocator: String?,
            sourceTitle: String?,
            importKind: String?,
        ): String {
            val cleanLocator = sourceLocator?.trim()?.takeIf { it.isNotBlank() } ?: return rawConfig
            return runCatching {
                val root = JSONObject(rawConfig)
                val meta = JSONObject().apply {
                    put(KEY_SOURCE_LOCATOR, cleanLocator)
                    sourceTitle?.trim()?.takeIf { it.isNotBlank() }?.let { put(KEY_SOURCE_TITLE, it) }
                    importKind?.trim()?.takeIf { it.isNotBlank() }?.let { put(KEY_IMPORT_KIND, it) }
                }
                root.put(KEY_META, meta)
                root.toString()
            }.getOrDefault(rawConfig)
        }

        fun copyMetadata(fromConfig: String, toConfig: String): String {
            val metadata = parse(fromConfig) ?: return toConfig
            return attach(
                rawConfig = toConfig,
                sourceLocator = metadata.sourceLocator,
                sourceTitle = metadata.sourceTitle,
                importKind = metadata.importKind,
            )
        }

        fun strip(rawConfig: String): String {
            return runCatching {
                val root = JSONObject(rawConfig)
                root.remove(KEY_META)
                root.toString()
            }.getOrDefault(rawConfig)
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).trim().takeIf { it.isNotBlank() }
        }
    }
}

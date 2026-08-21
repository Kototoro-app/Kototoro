package org.skepsun.kototoro.core.jsonsource

import org.skepsun.kototoro.core.db.entity.JsonSourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies and categorizes manga sources by their type (native, JSON, external).
 * 
 * This class provides methods to determine if a source is a JSON source based on its identifier,
 * and to retrieve appropriate type labels for UI display.
 */
@Singleton
class SourceTypeIdentifier @Inject constructor() {
    
    companion object {
        private const val JSON_PREFIX = "JSON_"
        private const val LEGADO_PREFIX = "JSON_LEGADO_"
        private const val LEGADO_MANGA_PREFIX = "JSON_LEGADO_M_"
        private const val TVBOX_PREFIX = "JSON_TVBOX_"
        private const val JS_PREFIX = "JSON_JS_"
        private const val LNREADER_PREFIX = "JSON_LNREADER_"
        private const val MIHON_PREFIX = "MIHON_"
        private const val ANIYOMI_PREFIX = "ANIYOMI_"
        private const val IREADER_PREFIX = "IREADER_"
        private const val CLOUDSTREAM_PREFIX = "CLOUDSTREAM_"
    }
    
    /**
     * Checks if a source is a JSON source based on its identifier.
     * 
     * @param sourceId The source identifier to check
     * @return true if the source is a JSON source (starts with JSON_ prefix)
     */
    fun isJsonSource(sourceId: String): Boolean {
        return sourceId.startsWith(JSON_PREFIX)
    }
    
    /**
     * Determines the type of a source based on its identifier.
     * 
     * @param sourceId The source identifier to analyze
     * @return The SourceType enum value representing the source's type
     */
    fun getSourceType(sourceId: String): SourceType {
        return when {
            sourceId.startsWith(CLOUDSTREAM_PREFIX) -> SourceType.CLOUDSTREAM
            sourceId.startsWith(IREADER_PREFIX) -> SourceType.IREADER
            sourceId.startsWith(MIHON_PREFIX) -> SourceType.MIHON
            sourceId.startsWith(ANIYOMI_PREFIX) -> SourceType.ANIYOMI
            sourceId.startsWith(LEGADO_MANGA_PREFIX) -> SourceType.JSON_LEGADO
            sourceId.startsWith(LEGADO_PREFIX) -> SourceType.JSON_LEGADO
            sourceId.startsWith(TVBOX_PREFIX) -> SourceType.JSON_TVBOX
            sourceId.startsWith(JS_PREFIX) -> SourceType.JSON_JS
            sourceId.startsWith(LNREADER_PREFIX) -> SourceType.JSON_LNREADER
            sourceId.startsWith(JSON_PREFIX) -> SourceType.JSON_LEGADO // Default JSON type
            sourceId.startsWith("LOCAL_") -> SourceType.NATIVE // Local sources are native
            else -> SourceType.NATIVE
        }
    }
    
    /**
     * Gets a human-readable label for the source type, suitable for UI display.
     * 
     * @param sourceId The source identifier
     * @return A localized string label for the source type
     */
    fun getSourceTypeLabel(sourceId: String): String {
        return when (getSourceType(sourceId)) {
            SourceType.NATIVE -> "原生源"
            SourceType.JSON_LEGADO -> "JSON 源 (Legado)"
            SourceType.JSON_TVBOX -> "JSON 源 (TVBox)"
            SourceType.JSON_JS -> "JavaScript 源"
            SourceType.JSON_LNREADER -> "LNReader 源"
            SourceType.EXTERNAL -> "外部源"
            SourceType.MIHON -> "Mihon 扩展"
            SourceType.ANIYOMI -> "Aniyomi 扩展"
            SourceType.IREADER -> "IReader 扩展"
            SourceType.CLOUDSTREAM -> "Cloudstream 扩展"
        }
    }
    
    /**
     * Gets the JsonSourceType enum from a source identifier.
     * 
     * @param sourceId The source identifier
     * @return The JsonSourceType if it's a JSON source, null otherwise
     */
    fun getJsonSourceType(sourceId: String): JsonSourceType? {
        return when (getSourceType(sourceId)) {
            SourceType.JSON_LEGADO -> JsonSourceType.LEGADO
            SourceType.JSON_TVBOX -> JsonSourceType.TVBOX
            SourceType.JSON_JS -> JsonSourceType.JS
            SourceType.JSON_LNREADER -> JsonSourceType.LNREADER
            else -> null
        }
    }
}

/**
 * Enum representing the different types of manga sources in the application.
 */
enum class SourceType {
    /**
     * Native Kotlin sources compiled into the application
     */
    NATIVE,
    
    /**
     * JSON sources using Legado format
     */
    JSON_LEGADO,
    
    /**
     * JSON sources using TVBox format
     */
    JSON_TVBOX,
    
    /**
     * Venera-style JavaScript source
     */
    JSON_JS,
    
    /**
     * LNReader JavaScript plugin source
     */
    JSON_LNREADER,
    
    /**
     * External sources (future use)
     */
    EXTERNAL,
    
    /**
     * Mihon extension sources
     */
    MIHON,

    /**
     * Aniyomi extension sources
     */
    ANIYOMI,

    /**
     * IReader extension sources
     */
    IREADER

    /**
     * Cloudstream video extension sources
     */
    ,CLOUDSTREAM
}

package org.skepsun.kototoro.core.jsonsource

import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.json.JSONObject

/**
 * Wrapper class that adapts a JsonSourceEntity to the ContentSource interface.
 * 
 * This allows JSON sources to be used interchangeably with native ContentParserSource
 * instances throughout the application.
 * 
 * @property entity The underlying JSON source entity from the database
 */
data class JsonContentSource(
    val entity: JsonSourceEntity
) : ContentSource {
    
    override val locale: String = ""
    override val contentType: ContentType
        get() = when (entity.type) {
            JsonSourceType.TVBOX -> ContentType.VIDEO
            JsonSourceType.JS -> ContentType.MANGA
            JsonSourceType.LNREADER -> ContentType.NOVEL
            JsonSourceType.LEGADO -> try {
                val jsonObj = JSONObject(entity.config)
                if (jsonObj.optInt("bookSourceType", 0) == 2) ContentType.MANGA else ContentType.NOVEL
            } catch (e: Exception) {
                ContentType.NOVEL
            }
        }

    /**
     * The source name, which is the unique identifier for JSON sources.
     * This follows the format: JSON_[TYPE_]NORMALIZED_NAME
     */
    override val name: String
        get() = entity.id
    
    /**
     * The display name for the source (the original, user-friendly name).
     */
    val displayName: String
        get() {
            val provider = tvBoxProviderName(entity.type, entity.config)
            return if (provider.isNullOrBlank() || entity.name.endsWith("（$provider）")) {
                entity.name
            } else {
                "${entity.name}（$provider）"
            }
        }
    
    /**
     * Whether this source is currently enabled.
     */
    val isEnabled: Boolean
        get() = entity.enabled
    
    /**
     * Whether this source is pinned.
     */
    val isPinned: Boolean
        get() = entity.isPinned

    internal fun isVisibleForTvBoxRepository(activeId: String?): Boolean =
        TVBoxRepositorySelector.isVisible(entity.type, entity.config, activeId)
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JsonContentSource) return false
        return entity.id == other.entity.id
    }
    
    override fun hashCode(): Int {
        return entity.id.hashCode()
    }
    
    override fun toString(): String {
        return "JsonContentSource(id=${entity.id}, name=${entity.name}, type=${entity.type})"
    }
}

private fun tvBoxProviderName(type: JsonSourceType, config: String): String? {
    if (type != JsonSourceType.TVBOX) {
        return null
    }
    return runCatching {
        TVBoxStoredConfig.parse(config).meta.sourceTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

data class JsonSourceListSource(
    private val summary: JsonSourceSummary,
) : ContentSource {

    override val locale: String = ""
    override val contentType: ContentType
        get() = when (summary.type) {
            JsonSourceType.TVBOX -> ContentType.VIDEO
            JsonSourceType.JS -> ContentType.MANGA
            JsonSourceType.LNREADER -> ContentType.NOVEL
            JsonSourceType.LEGADO -> ContentType.NOVEL
        }

    override val name: String
        get() = summary.id

    val displayName: String
        get() {
            val provider = tvBoxProviderName(summary.type, summary.config)
            return if (provider.isNullOrBlank() || summary.name.endsWith("（$provider）")) {
                summary.name
            } else {
                "${summary.name}（$provider）"
            }
        }

    val isEnabled: Boolean
        get() = summary.enabled

    val isPinned: Boolean
        get() = summary.isPinned

    val hasExploreUrl: Boolean
        get() = summary.hasExploreUrl

    val iconUrl: String?
        get() = summary.iconUrl

    internal fun isVisibleForTvBoxRepository(activeId: String?): Boolean =
        TVBoxRepositorySelector.isVisible(summary.type, summary.config, activeId)
}

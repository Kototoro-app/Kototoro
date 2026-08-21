package org.skepsun.kototoro.explore.ui.model

import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup

/**
 * Represents a filter option for sources in the explore page.
 */
sealed class SourceFilter(
    @StringRes val titleRes: Int,
    val id: String,
) {
    /**
     * Show all sources without filtering
     */
    object All : SourceFilter(R.string.all, "all")
    
    /**
     * Show only native sources
     */
    object Native : SourceFilter(R.string.native_sources, "native")
    
    /**
     * Show only JSON sources
     */
    object JsonOnly : SourceFilter(R.string.json_sources, "json")
    
    /**
     * Show only manga sources
     */
    object ContentOnly : SourceFilter(R.string.manga, "manga")
    
    /**
     * Show only novel sources
     */
    object NovelOnly : SourceFilter(R.string.novel, "novel")
    
    /**
     * Show only video sources
     */
    object VideoOnly : SourceFilter(R.string.video, "video")
    
    companion object {
        /**
         * Get all available filters in order
         */
        fun getAllFilters(): List<SourceFilter> = listOf(
            All,
            Native,
            JsonOnly,
            ContentOnly,
            NovelOnly,
            VideoOnly,
        )
        
        /**
         * Find filter by ID
         */
        fun fromId(id: String): SourceFilter = when (id) {
            "all" -> All
            "native" -> Native
            "json" -> JsonOnly
            "manga" -> ContentOnly
            "novel" -> NovelOnly
            "video" -> VideoOnly
            else -> All
        }
    }
    
    /**
     * Check if a source matches this filter based on content group
     */
    fun matchesContentGroup(group: ContentGroup): Boolean = when (this) {
        All, Native, JsonOnly -> true
        ContentOnly -> group == ContentGroup.MANGA || group == ContentGroup.HENTAI_MANGA
        NovelOnly -> group == ContentGroup.NOVEL || group == ContentGroup.HENTAI_NOVEL
        VideoOnly -> group == ContentGroup.VIDEO || group == ContentGroup.HENTAI_VIDEO
    }
    
    /**
     * Check if a source matches this filter based on origin group
     */
    fun matchesOriginGroup(group: OriginGroup): Boolean = when (this) {
        All -> true
        Native -> group == OriginGroup.NATIVE
        JsonOnly -> group == OriginGroup.LEGADO_JSON || group == OriginGroup.TVBOX_JSON || group == OriginGroup.JS_JSON || group == OriginGroup.LNREADER_JSON
        ContentOnly, NovelOnly, VideoOnly -> true // Content filters don't filter by origin
    }
}

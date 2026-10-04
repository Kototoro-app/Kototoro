package org.skepsun.kototoro.core.jsonsource

/**
 * Enum representing content type groups for sources.
 */
enum class ContentGroup {
    /**
     * Content, manhwa, manhua, comics, and related visual content
     */
    MANGA,

    /**
     * Novel and text-based content
     */
    NOVEL,

    /**
     * Video content
     */
    VIDEO,

    /**
     * Hentai manga
     */
    HENTAI_MANGA,

    /**
     * Hentai novel
     */
    HENTAI_NOVEL,

    /**
     * Hentai video
     */
    HENTAI_VIDEO,

    /**
     * Other or unclassified content
     */
    OTHER
}

/**
 * Enum representing origin type groups for sources.
 */
enum class OriginGroup {
    /**
     * Native Kotlin sources compiled into the application
     */
    NATIVE,

    /**
     * JSON sources using Legado format
     */
    LEGADO_JSON,

    /**
     * JSON sources using TVBox format
     */
    TVBOX_JSON,

    /**
     * JavaScript sources (Venera style)
     */
    JS_JSON,

    /**
     * External sources
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
    IREADER,

    /**
     * Cloudstream extension sources
     */
    CLOUDSTREAM,

    /**
     * JSON sources using LNReader format
     */
    LNREADER_JSON,

    /**
     * Tsundoku (Tachiyomi novel ABI) extension sources
     */
    TSUNDOKU,
}

/**
 * Sealed class representing different types of source groups.
 */
sealed class SourceGroup {
    /**
     * Group by content type (manga, novel, video)
     */
    data class Content(val type: ContentGroup) : SourceGroup()

    /**
     * Group by origin type (native, JSON Legado, JSON TVBox)
     */
    data class Origin(val type: OriginGroup) : SourceGroup()
}

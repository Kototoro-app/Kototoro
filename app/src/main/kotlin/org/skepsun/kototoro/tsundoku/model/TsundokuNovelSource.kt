package org.skepsun.kototoro.tsundoku.model

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiXSourceAdapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Wrapper that adapts a loaded Tsundoku novel-source (a Tachiyomi-ABI [Source]) to Kototoro's
 * [ContentSource] interface.
 *
 * This is the novel counterpart of
 * [org.skepsun.kototoro.mihon.model.MihonMangaSource]: it lets Tsundoku novel extensions be used
 * interchangeably with native Kototoro sources throughout the application.
 *
 * Implements [TachiyomiXSourceAdapter] so request contexts (SourceRequestContext /
 * MihonRequestContext), the network client and the browser/Cookie bridge can treat it through the
 * ecosystem-neutral seam (plan §6.2, T1.3/T1.4) instead of hard-casting to this class.
 *
 * Reading operations (popular / search / latest / lists) belong to Phase 3B of the Tsundoku
 * integration plan and are intentionally not implemented here.
 */
data class TsundokuNovelSource(
    /** The raw upstream Tsundoku extension source instance (a `SourceFactory` result). */
    override val upstreamSource: Source,
    val pkgName: String,
    val isNsfw: Boolean = false,
    /**
     * Whether this source should display its language in the name.
     * Used for multi-language extensions where the same source name appears multiple times.
     */
    val hasLanguageSuffix: Boolean = false,
) : ContentSource, TachiyomiXSourceAdapter {

    override val locale: String get() = language
    override val contentType: ContentType get() = if (isNsfw) ContentType.HENTAI_NOVEL else ContentType.NOVEL

    /** Tachiyomi-ABI ecosystem of this source (always Tsundoku here). */
    override val ecosystem: ExternalExtensionType get() = ExternalExtensionType.TSUNDOKU

    override val packageName: String get() = pkgName

    /** Base URL of the website when the extension is an HttpSource; null otherwise. */
    override val baseUrlOrNull: String? get() = (upstreamSource as? HttpSource)?.baseUrl

    /** Source-level preference namespace: `tsundoku:packageName:sourceId` (plan §6.2). */
    override val preferenceNamespace: String get() = "tsundoku:$pkgName:${upstreamSource.id}"

    /** The unique source ID from Tsundoku. */
    override val sourceId: Long
        get() = upstreamSource.id

    /** Kototoro-visible source key, identical to the legacy `name` (`TSUNDOKU_{id}`). */
    override val sourceKey: String get() = name

    /**
     * The source name, which follows the Tsundoku convention: TSUNDOKU_{sourceId}
     */
    override val name: String
        get() = "TSUNDOKU_${upstreamSource.id}"

    /**
     * The display name for the source (from Tsundoku).
     * If hasLanguageSuffix is true, appends the language name.
     */
    val displayName: String
        get() = if (hasLanguageSuffix) {
            "${upstreamSource.name} (${getLanguageDisplayName(language)})"
        } else {
            upstreamSource.name
        }

    /**
     * The language code (ISO 639-1).
     */
    val language: String
        get() = upstreamSource.lang

    /**
     * Whether this source supports latest updates.
     */
    val supportsLatest: Boolean
        get() = upstreamSource.supportsLatest

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentSource) return false
        // Compare by name to support comparison with anonymous ContentSource objects
        // that are created when loading from the database
        return name == other.name
    }

    override fun hashCode(): Int {
        // Use name for hashCode to be consistent with equals
        return name.hashCode()
    }

    override fun toString(): String {
        return "TsundokuNovelSource(id=${upstreamSource.id}, name=${upstreamSource.name}, lang=$language)"
    }

    companion object {
        /**
         * Convert ISO 639-1 language code to display name.
         */
        fun getLanguageDisplayName(langCode: String): String {
            return getExternalExtensionLanguageDisplayName(langCode)
        }
    }
}

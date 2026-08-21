package org.skepsun.kototoro.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.skepsun.kototoro.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Wrapper that adapts a Mihon CatalogueSource to Kototoro's ContentSource interface.
 *
 * This allows Mihon sources to be used interchangeably with native Kototoro sources
 * throughout the application.
 */
data class MihonMangaSource(
    val catalogueSource: CatalogueSource,
    val pkgName: String,
    val isNsfw: Boolean = false,
    /**
     * Whether this source should display its language in the name.
     * Used for multi-language extensions where the same source name appears multiple times.
     */
    val hasLanguageSuffix: Boolean = false,
) : ContentSource {

    override val locale: String get() = language
    override val contentType: ContentType get() = if (isNsfw) ContentType.HENTAI_MANGA else ContentType.MANGA

    /**
     * The source name, which follows the Mihon convention: MIHON_{sourceId}
     */
    override val name: String
        get() = "MIHON_${catalogueSource.id}"

    /**
     * The display name for the source (from Mihon).
     * If hasLanguageSuffix is true, appends the language name.
     */
    val displayName: String
        get() = if (hasLanguageSuffix) {
            "${catalogueSource.name} (${getLanguageDisplayName(language)})"
        } else {
            catalogueSource.name
        }

    /**
     * The language code (ISO 639-1).
     */
    val language: String
        get() = catalogueSource.lang

    /**
     * The unique source ID from Mihon.
     */
    val sourceId: Long
        get() = catalogueSource.id

    /**
     * Whether this source supports latest updates.
     */
    val supportsLatest: Boolean
        get() = catalogueSource.supportsLatest

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
        return "MihonMangaSource(id=${catalogueSource.id}, name=${catalogueSource.name}, lang=$language)"
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

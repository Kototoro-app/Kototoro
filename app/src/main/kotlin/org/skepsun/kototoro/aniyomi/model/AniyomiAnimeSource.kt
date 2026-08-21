package org.skepsun.kototoro.aniyomi.model

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import org.skepsun.kototoro.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Wrapper that adapts an Aniyomi AnimeCatalogueSource to Kototoro's ContentSource interface.
 */
data class AniyomiAnimeSource(
    val animeCatalogueSource: AnimeCatalogueSource,
    val pkgName: String,
    val isNsfw: Boolean = false,
    /**
     * Whether this source should display its language in the name.
     */
    val hasLanguageSuffix: Boolean = false,
) : ContentSource {

    override val locale: String get() = language
    override val contentType: ContentType get() = if (isNsfw) ContentType.HENTAI_VIDEO else ContentType.VIDEO

    /**
     * The source name, following the Aniyomi convention: ANIYOMI_{sourceId}
     */
    override val name: String
        get() = "ANIYOMI_${animeCatalogueSource.id}"

    /**
     * The display name for the source.
     */
    val displayName: String
        get() = if (hasLanguageSuffix) {
            "${animeCatalogueSource.name} (${getLanguageDisplayName(language)})"
        } else {
            animeCatalogueSource.name
        }

    /**
     * The language code.
     */
    val language: String
        get() = animeCatalogueSource.lang

    /**
     * The unique source ID.
     */
    val sourceId: Long
        get() = animeCatalogueSource.id

    /**
     * Whether this source supports latest updates.
     */
    val supportsLatest: Boolean
        get() = animeCatalogueSource.supportsLatest

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentSource) return false
        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return "AniyomiAnimeSource(id=${animeCatalogueSource.id}, name=${animeCatalogueSource.name}, lang=$language)"
    }

    companion object {
        fun getLanguageDisplayName(langCode: String): String {
            return getExternalExtensionLanguageDisplayName(langCode)
        }
    }
}

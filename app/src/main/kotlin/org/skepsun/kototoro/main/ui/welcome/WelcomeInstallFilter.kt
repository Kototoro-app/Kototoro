package org.skepsun.kototoro.main.ui.welcome

import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind

/**
 * Pure decision logic that the setup-wizard batch-install page uses to narrow
 * the install plan (and the counts shown on its chips / summary) by the
 * content-type filter.
 */
internal object WelcomeInstallFilter {

    data class LanguageSelection(
        val languageCodes: Set<String>,
        val includesUniversalLanguage: Boolean,
    )

    /**
     * Expands the user-selected content types with their adult variants.
     * An empty selection means no content families are enabled.
     */
    fun expandTypes(selectedTypes: Set<ContentType>): Set<ContentType> {
        if (selectedTypes.isEmpty()) {
            return emptySet()
        }
        return selectedTypes.flatMapTo(HashSet()) { type ->
            when (type) {
                ContentType.MANGA -> listOf(ContentType.MANGA, ContentType.HENTAI_MANGA)
                ContentType.NOVEL -> listOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
                ContentType.VIDEO -> listOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
                else -> listOf(type)
            }
        }
    }

    /**
     * Whether [kind] should be skipped from the install plan because none of
     * its typical content types is covered by [expandedSelectedTypes].
     * An empty expanded selection excludes every ecosystem with a known content family.
     */
    fun excludesKind(kind: UnifiedSourceKind, expandedSelectedTypes: Set<ContentType>): Boolean {
        if (expandedSelectedTypes.isEmpty()) {
            return contentTypesOf(kind).isNotEmpty()
        }
        val kindTypes = contentTypesOf(kind)
        return kindTypes.isNotEmpty() && kindTypes.none { it in expandedSelectedTypes }
    }

    /**
     * Unknown metadata is kept to avoid false negatives and is filtered after installation from
     * the real sources. Known packages must intersect a concrete language selection or contain a
     * universal source when the user selected the multilingual chip.
     */
    fun matchesLanguages(
        packageLanguages: Set<String>,
        packageIncludesUniversalLanguage: Boolean,
        isPackageMetadataKnown: Boolean,
        selection: LanguageSelection,
    ): Boolean {
        if (selection.languageCodes.isEmpty() && !selection.includesUniversalLanguage) {
            return false
        }
        if (!isPackageMetadataKnown) {
            return true
        }
        if (packageIncludesUniversalLanguage && selection.includesUniversalLanguage) return true
        return packageLanguages.any { packageLanguage ->
            selection.languageCodes.any { selectedLanguage ->
                packageLanguage == selectedLanguage ||
                    packageLanguage.startsWith("$selectedLanguage-") ||
                    selectedLanguage.startsWith("$packageLanguage-")
            }
        }
    }

    /**
     * Which content types each ecosystem typically provides, matching the
     * wizard's own ecosystem docs (manga: JAR / Mihon / IReader / LNReader,
     * novel: Legado / IReader / LNReader, video: Aniyomi / TVBox / Cloudstream).
     */
    fun contentTypesOf(kind: UnifiedSourceKind): Set<ContentType> = when (kind) {
        UnifiedSourceKind.JAR,
        UnifiedSourceKind.MIHON,
        -> setOf(ContentType.MANGA)
        UnifiedSourceKind.LEGADO -> setOf(ContentType.NOVEL)
        UnifiedSourceKind.TVBOX,
        UnifiedSourceKind.ANIYOMI,
        UnifiedSourceKind.CLOUDSTREAM,
        -> setOf(ContentType.VIDEO)
        UnifiedSourceKind.IREADER -> setOf(ContentType.MANGA, ContentType.NOVEL)
        UnifiedSourceKind.LNREADER -> setOf(ContentType.MANGA, ContentType.NOVEL)
        UnifiedSourceKind.TSUNDOKU -> setOf(ContentType.NOVEL)
        UnifiedSourceKind.JS,
        UnifiedSourceKind.NATIVE,
        -> emptySet()
    }
}

package org.skepsun.kototoro.settings.sources.unified

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

/**
 * Preset/recommended extension repositories.
 *
 * The preset list is intentionally EMPTY. Kototoro does not ship, curate or recommend any
 * third-party repository — not even TVBox or Legado — so the app never acts as a curated
 * entry point to third-party content aggregators (a copyright risk, e.g. the Kakao takedowns).
 *
 * Users opt-in explicitly by adding their own repositories (URL / inline / file) in
 * Settings › Content sources. Repositories the user has already added live in the user's own
 * settings/database and are in no way affected by this empty preset list.
 *
 * A community-maintained repository directory exists as a separate repository
 * (`kototoro-repo-hub`): a VitePress/GitHub Pages site with install buttons that link back
 * into the unified source manager via `kototoro://add-repo?url=...&kind=...`. The app never
 * points to that site or ships its contents.
 *
 * `withPresetRepositories` and the various `firstOrNull { preset -> ... }` lookups in the
 * unified catalog already handle an empty list gracefully (presets simply match nothing), so
 * existing user-configured repositories keep working with `isPreset = false`.
 */
object UnifiedRecommendedRepositories {

    val all: List<UnifiedRecommendedRepository> = emptyList()

    fun byKind(kind: UnifiedSourceKind): List<UnifiedRecommendedRepository> {
        return all.filter { it.kind == kind }
    }

    fun byExternalType(type: ExternalExtensionType): List<UnifiedRecommendedRepository> {
        return byKind(
            when (type) {
                ExternalExtensionType.MIHON -> UnifiedSourceKind.MIHON
                ExternalExtensionType.ANIYOMI -> UnifiedSourceKind.ANIYOMI
                ExternalExtensionType.IREADER -> UnifiedSourceKind.IREADER
                ExternalExtensionType.JAR -> UnifiedSourceKind.JAR
                ExternalExtensionType.CLOUDSTREAM -> UnifiedSourceKind.CLOUDSTREAM
                ExternalExtensionType.TSUNDOKU -> UnifiedSourceKind.TSUNDOKU
            },
        )
    }
}

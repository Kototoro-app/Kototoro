package org.skepsun.kototoro.settings.sources.unified

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

/**
 * The preset/recommended repository list is intentionally EMPTY: Kototoro never ships, curates or
 * recommends any third-party extension repository (a copyright risk — e.g. Kakao takedowns), so
 * every repository is an explicit user action.
 *
 * Completion gate: recommended repositories are presets and must NEVER be auto-added or persisted.
 * [UnifiedRecommendedRepository] is a pure in-memory descriptor with no `isConfigured` field and no
 * backing repository, so simply listing an entry in [UnifiedRecommendedRepositories.all] cannot
 * write to [org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository] nor to any other
 * store. Keeping the list empty guarantees no repository is ever offered or configured implicitly.
 * Any future re-introduction of presets must be guarded by these tests.
 */
class UnifiedRecommendedRepositoriesTest {

    @Test
    fun `preset repository list is empty`() {
        assertEquals(emptyList<UnifiedRecommendedRepository>(), UnifiedRecommendedRepositories.all)
    }

    @Test
    fun `byKind returns no presets for every kind`() {
        UnifiedSourceKind.entries.forEach { kind ->
            assertTrue(
                UnifiedRecommendedRepositories.byKind(kind).isEmpty(),
                "Expected no ${kind.name} presets, but found: ${UnifiedRecommendedRepositories.byKind(kind)}",
            )
        }
    }

    @Test
    fun `byExternalType returns no presets for every extension type`() {
        ExternalExtensionType.entries.forEach { type ->
            assertTrue(
                UnifiedRecommendedRepositories.byExternalType(type).isEmpty(),
                "Expected no ${type} presets, but found: ${UnifiedRecommendedRepositories.byExternalType(type)}",
            )
        }
    }

    @Test
    fun `no third-party repository is recommended`() {
        assertTrue(
            UnifiedRecommendedRepositories.all.none { item -> item.url.isNotBlank() },
            "A recommended repository must never be shipped inside the app",
        )
    }
}

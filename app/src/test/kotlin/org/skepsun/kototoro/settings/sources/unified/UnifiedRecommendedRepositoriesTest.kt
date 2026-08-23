package org.skepsun.kototoro.settings.sources.unified

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

/**
 * Verifies that the NovelSourcery (Tsundoku novel) repository is exposed as a *recommended* preset.
 *
 * Completion gate: recommended repositories are presets and must NEVER be auto-added or persisted.
 * [UnifiedRecommendedRepository] is a pure in-memory descriptor with no `isConfigured` field and no
 * backing repository, so simply listing an entry in [UnifiedRecommendedRepositories.all] cannot
 * write to [org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository] nor to any other
 * store. A repository only becomes configured when the user explicitly adds it; the unified catalog
 * then renders it with `isConfigured = true` (presets never added stay `isConfigured = false` via
 * `UnifiedSourceCatalogRepository.withPresetRepositories`). Any future persistence or auto-add
 * inside this object must be guarded by these tests.
 */
class UnifiedRecommendedRepositoriesTest {

    @Test
    fun `all includes a TSUNDOKU recommendation ending in index pb`() {
        val recommendation = UnifiedRecommendedRepositories.all.single { item ->
            item.kind == UnifiedSourceKind.TSUNDOKU && item.url.endsWith("index.pb")
        }

        assertEquals(UnifiedSourceKind.TSUNDOKU, recommendation.kind)
        assertEquals("NovelSourcery (Tsundoku novels)", recommendation.name)
        assertEquals(
            "https://github.com/NovelSourcery/extensions/raw/repo/index.pb",
            recommendation.url,
        )
        assertEquals(UnifiedRepositoryLocationType.REMOTE_URL, recommendation.locationType)
        assertTrue(recommendation.capabilities.isNotEmpty())
    }

    @Test
    fun `byExternalType TSUNDOKU returns the same recommendation`() {
        val byExternalType = UnifiedRecommendedRepositories.byExternalType(ExternalExtensionType.TSUNDOKU)
        val fromAll = UnifiedRecommendedRepositories.all.filter { item ->
            item.kind == UnifiedSourceKind.TSUNDOKU && item.url.endsWith("index.pb")
        }

        assertNotNull(byExternalType)
        assertEquals(fromAll, byExternalType)
        assertTrue(byExternalType.isNotEmpty())
    }

    @Test
    fun `recommended TSUNDOKU repository is not auto configured`() {
        // `UnifiedRecommendedRepository` carries no `isConfigured` field: a recommendation is a pure
        // preset descriptor. Listing it here performs no persistence and triggers no writes to
        // ExternalExtensionRepoRepository; the catalog keeps it `isConfigured = false` until the
        // user explicitly adds it (see the class kdoc for the completion gate).
        val recommendation = UnifiedRecommendedRepositories.all.single { item ->
            item.kind == UnifiedSourceKind.TSUNDOKU && item.url.endsWith("index.pb")
        }

        assertEquals(
            UnifiedRecommendedRepositories.byExternalType(ExternalExtensionType.TSUNDOKU),
            listOf(recommendation),
        )
        assertTrue(recommendation.url.endsWith("index.pb"))
    }
}

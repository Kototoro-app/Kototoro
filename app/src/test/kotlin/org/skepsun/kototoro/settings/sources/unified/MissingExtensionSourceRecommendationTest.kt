package org.skepsun.kototoro.settings.sources.unified

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissingExtensionSourceRecommendationTest {

    @Test
    fun `fork built-in ids use the ordinary missing Mihon recommendation policy`() {
        val installedSourceIds = emptySet<Long>()

        assertTrue(shouldRecommendMissingExtensionSource(6902L, installedSourceIds))
        assertTrue(shouldRecommendMissingExtensionSource(6_225_928_719_850_211_219L, installedSourceIds))
        assertTrue(shouldRecommendMissingExtensionSource(2_499_283_573_021_220_255L, installedSourceIds))
    }

    @Test
    fun `installed Mihon source is not recommended again`() {
        val sourceId = 6902L

        assertFalse(shouldRecommendMissingExtensionSource(sourceId, setOf(sourceId)))
    }

    @Test
    fun `candidates come only from source keys still referenced by user state`() {
        val candidates = missingExtensionSourceCandidates(
            referencedSourceKeys = listOf("MIHON_6902"),
            displayNamesByKey = mapOf("MIHON_6902" to "漫画柜", "MIHON_1234" to "Deleted favourite source"),
            installedSourceIds = emptySet(),
        )

        assertEquals(
            listOf(MissingExtensionSourceCandidate("MIHON_6902", 6902L, UnifiedSourceKind.MIHON, "漫画柜")),
            candidates,
        )
    }

    @Test
    fun `candidates keep the extension kind and skip installed or non extension sources`() {
        val candidates = missingExtensionSourceCandidates(
            referencedSourceKeys = listOf("ANIYOMI_42", "MIHON_7", "MIHON_7", "MANGADEX", "MIHON_abc", "IREADER_5"),
            displayNamesByKey = emptyMap(),
            installedSourceIds = setOf(7L),
        )

        assertEquals(
            listOf(MissingExtensionSourceCandidate("ANIYOMI_42", 42L, UnifiedSourceKind.ANIYOMI, null)),
            candidates,
        )
    }

    @Test
    fun `catalog source name replaces a stable source key`() {
        assertEquals(
            "漫画柜",
            resolveMissingExtensionSourceLabel(
                sourceKey = "MIHON_6902",
                persistedDisplayName = null,
                catalogNamesById = mapOf(6902L to "漫画柜"),
            ),
        )
    }

    @Test
    fun `stable source key is not treated as a display name`() {
        assertEquals(
            "漫画柜",
            resolveMissingExtensionSourceLabel(
                sourceKey = "MIHON_6902",
                persistedDisplayName = "MIHON_6902",
                catalogNamesById = mapOf(6902L to "漫画柜"),
            ),
        )
    }
}

package org.skepsun.kototoro.settings.sources.unified

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
}

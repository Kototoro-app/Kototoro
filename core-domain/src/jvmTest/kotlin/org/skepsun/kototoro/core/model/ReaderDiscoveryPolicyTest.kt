package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDiscoveryPolicyTest {
    @Test
    fun `all formats still exclude airing anime and movie catalogs`() {
        for (id in listOf("al_anime_airing", "anime_upcoming", "calendar_2026-11-10", "seasonal", "top_movie", "top_tv")) {
            assertFalse(ReaderDiscoveryPolicy.acceptsCategory(id, "ANILIST"), id)
        }
    }

    @Test
    fun `manga and book recommendations remain available`() {
        for ((service, id) in listOf("KITSU" to "manga_popular", "MAL" to "top_manga", "ANILIST" to "al_manga", "BANGUMI" to "book", "ANILIST" to "light_novel")) {
            assertTrue(ReaderDiscoveryPolicy.acceptsCategory(id, service), "$service / $id")
        }
    }

    @Test
    fun `anime only services cannot reappear through empty filter fallback`() {
        assertFalse(ReaderDiscoveryPolicy.acceptsCategory("trending", "SIMKL"))
        assertFalse(ReaderDiscoveryPolicy.acceptsCategory(null, "SIMKL"))
        assertFalse(ReaderDiscoveryPolicy.acceptsCategory("popular", "KITSU"))
    }

    @Test
    fun `default book searches remain supported without a category identifier`() {
        assertTrue(ReaderDiscoveryPolicy.acceptsCategory(null, "MAL"))
        assertTrue(ReaderDiscoveryPolicy.acceptsCategory(null, "KITSU"))
    }
}

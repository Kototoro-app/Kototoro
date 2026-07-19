package org.skepsun.kototoro.discover.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscoverHeroCarouselTest {

    @Test
    fun `blend alpha stays translucent and follows preference strength`() {
        assertEquals(0.44f, resolveDiscoverHeroBlendAlpha(0.88f, 0.5f))
        assertEquals(0f, resolveDiscoverHeroBlendAlpha(0.88f, 0f))
    }

    @Test
    fun `blend alpha clamps invalid inputs`() {
        assertEquals(1f, resolveDiscoverHeroBlendAlpha(1.4f, 1.4f))
        assertEquals(0f, resolveDiscoverHeroBlendAlpha(-0.2f, 0.8f))
    }

    @Test
    fun `background becomes transparent at full gradient strength`() {
        assertEquals(1f, resolveDiscoverHeroBackgroundEndAlpha(0f))
        assertEquals(0f, resolveDiscoverHeroBackgroundEndAlpha(1f))
        assertEquals(0f, resolveDiscoverHeroBackgroundEndAlpha(1.4f))
    }
}

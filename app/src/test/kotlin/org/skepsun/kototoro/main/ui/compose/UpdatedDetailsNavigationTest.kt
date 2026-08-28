package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.details.ui.model.DetailsOrigin

class UpdatedDetailsNavigationTest {

    @Test
    fun `updated entity details use nav3 origin and preserve hero key`() {
        var capturedOrigin: DetailsOrigin? = null
        var capturedSharedElementKey: String? = null

        navigateUpdatedEntityDetails(
            entityId = 42L,
            preferredLocalMangaId = 7L,
            initialProjectionLocalMangaId = 9L,
            sharedElementKey = "updated-cover-hero",
        ) { origin, sharedElementKey ->
            capturedOrigin = origin
            capturedSharedElementKey = sharedElementKey
        }

        val entityOrigin = assertInstanceOf(
            DetailsOrigin.EntityGraph::class.java,
            requireNotNull(capturedOrigin),
        )
        assertEquals(42L, entityOrigin.entityId)
        assertEquals(7L, entityOrigin.preferredLocalMangaId)
        assertEquals(9L, entityOrigin.initialProjectionLocalMangaId)
        assertEquals("updated-cover-hero", capturedSharedElementKey)
    }
}

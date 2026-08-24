package org.skepsun.kototoro.scrobbling.common.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrobblingInfoIdentityTest {

    private fun info(
        entityId: Long? = null,
        preferredLocalMangaId: Long? = null,
        mangaId: Long = 1L,
        targetId: Long = 2L,
        mediaType: String? = "manga",
        status: ScrobblingStatus? = ScrobblingStatus.READING,
    ) = ScrobblingInfo(
        scrobbler = ScrobblerService.MAL,
        entityId = entityId,
        preferredLocalMangaId = preferredLocalMangaId,
        mangaId = mangaId,
        targetId = targetId,
        status = status,
        chapter = 1,
        comment = null,
        rating = 0f,
        title = "Title #$targetId",
        coverUrl = "",
        description = null,
        externalUrl = "",
        mediaType = mediaType,
    )

    @Test
    fun `keys are unique when rows differ only in entityId`() {
        // Regression for "info:... key was already used": two DB rows can map to the
        // same (scrobbler, targetId, mangaId, mediaType) but carry a different
        // entity-graph entityId. They must still get distinct LazyColumn keys.
        val a = info(entityId = null)
        val b = info(entityId = 42L)

        assertNotEquals(a.identityKey(), b.identityKey())
    }

    @Test
    fun `keys are unique when rows differ only in preferredLocalMangaId`() {
        val a = info(preferredLocalMangaId = 7L)
        val b = info(preferredLocalMangaId = 8L)

        assertNotEquals(a.identityKey(), b.identityKey())
    }

    @Test
    fun `keys are unique for every identity-distinct item`() {
        val items = listOf(
            info(targetId = 44347L, mediaType = "manga"),
            info(targetId = 44347L, mediaType = "anime"),
            info(targetId = 44348L, mediaType = "manga"),
            info(mangaId = 2142881150806199867L, targetId = 44347L, mediaType = "manga"),
            info(entityId = 5L, targetId = 44347L, mediaType = "manga"),
        )

        assertEquals(items.size, items.map { it.identityKey() }.distinct().size)
    }

    @Test
    fun `duplicate rows collapse when deduped by identityKey`() {
        // Simulates duplicate `scrobblings` rows for the same remote entry that share
        // the visible identity but differ only in backend-only columns (rate id/ownerId).
        val one = info(entityId = null)
        val duplicate = info(entityId = null)

        assertEquals(one.identityKey(), duplicate.identityKey())
        assertEquals(1, listOf(one, duplicate).distinctBy { it.identityKey() }.size)
    }

    @Test
    fun `areItemsTheSame agrees with identityKey`() {
        val a = info(entityId = null)
        val b = info(entityId = 3L)

        assertTrue(a.areItemsTheSame(info(entityId = null)))
        assertTrue(!a.areItemsTheSame(b))
    }
}

package org.skepsun.kototoro.scrobbling.common.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrobblingInfoIdentityTest {

    private fun info(
        preferredLocalMangaId: Long? = null,
        mangaId: Long = 1L,
        targetId: Long = 2L,
        mediaType: String? = "manga",
        status: ScrobblingStatus? = ScrobblingStatus.READING,
    ) = ScrobblingInfo(
        scrobbler = ScrobblerService.MAL,
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
            info(preferredLocalMangaId = 5L, targetId = 44347L, mediaType = "manga"),
        )

        assertEquals(items.size, items.map { it.identityKey() }.distinct().size)
    }

    @Test
    fun `duplicate rows collapse when deduped by identityKey`() {
        // Simulates duplicate `scrobblings` rows for the same remote entry that share
        // the visible identity but differ only in backend-only columns (rate id).
        val one = info()
        val duplicate = info()

        assertEquals(one.identityKey(), duplicate.identityKey())
        assertEquals(1, listOf(one, duplicate).distinctBy { it.identityKey() }.size)
    }

    @Test
    fun `areItemsTheSame agrees with identityKey`() {
        val a = info()
        val b = info(preferredLocalMangaId = 3L)

        assertTrue(a.areItemsTheSame(info()))
        assertTrue(!a.areItemsTheSame(b))
    }
}

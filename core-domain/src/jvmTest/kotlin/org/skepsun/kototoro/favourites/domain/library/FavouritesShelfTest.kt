package org.skepsun.kototoro.favourites.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * The "continue reading" shelf above a favourites category: works with new chapters first,
 * then works being read, never works that are finished, untouched or merely pinned (pinned
 * works already lead the grid right below the shelf).
 */
class FavouritesShelfTest {

    private fun row(
        entityId: Long,
        newChapters: Int = 0,
        lastChapterDate: Long = 0L,
        lastReadAt: Long? = null,
        progressPercent: Float? = null,
        isPinned: Boolean = false,
    ) = FavouriteCardRow(
        entityId = entityId,
        displayMangaId = 100L + entityId,
        localMangaIds = setOf(100L + entityId),
        title = "Work $entityId",
        altTitle = null,
        coverUrl = null,
        author = null,
        sourceName = "TEST",
        sourceGroupFlags = 0,
        sourceOriginFlags = 0,
        contentType = ContentType.MANGA,
        publicationState = ContentState.ONGOING,
        isNsfw = false,
        rating = -1f,
        readingStatus = "READING",
        newChapters = newChapters,
        lastChapterDate = lastChapterDate,
        progressPercent = progressPercent,
        progressTotalChapters = null,
        lastReadAt = lastReadAt,
        tagIds = emptySet(),
        displayTags = emptyList(),
        isDownloaded = false,
        overrideTitle = null,
        overrideCoverUrl = null,
        metadataTrackingService = null,
        metadataTrackingTitle = null,
        metadataTrackingCoverUrl = null,
        isPinned = isPinned,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `updated works lead the shelf, newest chapter first`() {
        val rows = listOf(
            row(1, newChapters = 2, lastChapterDate = 10L),
            row(2, lastReadAt = 500L, progressPercent = 0.3f),
            row(3, newChapters = 1, lastChapterDate = 30L),
        )

        val shelf = selectFavouritesShelfRows(rows)

        assertEquals(listOf(3L, 1L, 2L), shelf.map { it.entityId })
    }

    @Test
    fun `works being read follow, most recently read first`() {
        val rows = listOf(
            row(1, lastReadAt = 100L, progressPercent = 0.1f),
            row(2, lastReadAt = 300L, progressPercent = 0.5f),
            row(3, lastReadAt = 200L),
        )

        val shelf = selectFavouritesShelfRows(rows)

        assertEquals(listOf(2L, 3L, 1L), shelf.map { it.entityId })
    }

    @Test
    fun `finished, untouched and pinned-only works stay off the shelf`() {
        val rows = listOf(
            row(1, lastReadAt = 100L, progressPercent = 1f),
            row(2),
            row(3, isPinned = true),
            row(4, lastReadAt = 0L),
        )

        assertTrue(selectFavouritesShelfRows(rows).isEmpty())
    }

    @Test
    fun `shelf is capped`() {
        val rows = (1L..30L).map { row(it, lastReadAt = it) }

        val shelf = selectFavouritesShelfRows(rows, limit = 5)

        assertEquals(listOf(30L, 29L, 28L, 27L, 26L), shelf.map { it.entityId })
    }
}

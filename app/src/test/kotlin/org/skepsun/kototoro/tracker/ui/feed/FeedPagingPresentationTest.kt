package org.skepsun.kototoro.tracker.ui.feed

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import java.time.Instant

class FeedPagingPresentationTest {

    @Test
    fun `date header is not duplicated across paging boundaries`() = runTest {
        val items = listOf(
            feedItem(1L, Instant.parse("2026-08-28T10:00:00Z")),
            feedItem(2L, Instant.parse("2026-08-28T09:00:00Z")),
            feedItem(3L, Instant.parse("2026-08-27T10:00:00Z")),
        )

        val snapshot = flowOf(PagingData.from(items))
            .map { it.applyFeedPagingPresentation() }
            .asSnapshot()

        assertEquals(items, snapshot.filterIsInstance<FeedItem>())
        assertEquals(2, snapshot.count { it is ListHeader })
        assertTrue(snapshot.first() is ListHeader)
    }

    private fun feedItem(id: Long, createdAt: Instant) = FeedItem(
        id = id,
        entityId = id,
        preferredLocalMangaId = id,
        override = null,
        manga = Content(
            id = id,
            title = "Work $id",
            altTitles = emptySet(),
            url = "/$id",
            publicUrl = "https://example.org/$id",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = TestSource,
        ),
        createdAt = createdAt,
        count = 1,
        isNew = true,
    )

    private object TestSource : ContentSource {
        override val name: String = "test"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}

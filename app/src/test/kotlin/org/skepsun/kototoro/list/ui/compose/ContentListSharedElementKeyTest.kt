package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

class ContentListSharedElementKeyTest {

    @Test
    fun `tracking metadata changes keep the same list transition key`() {
        val beforeBinding = listItem(
            uiId = -257L,
            contentId = 10L,
            source = LocalSource,
            coverUrl = "https://local.test/cover.jpg",
            trackingService = null,
        )
        val afterBinding = listItem(
            uiId = -257L,
            contentId = 20L,
            source = TrackingSource,
            coverUrl = "https://tracking.test/cover.jpg",
            trackingService = ScrobblerService.ANILIST,
        )

        assertEquals(
            contentListSharedElementKey(beforeBinding, "favorites"),
            contentListSharedElementKey(afterBinding, "favorites"),
        )
    }

    @Test
    fun `different grouped entities never share a transition key`() {
        val first = listItem(-257L, 10L, LocalSource, "https://same.test/cover.jpg", null)
        val second = listItem(-513L, 11L, LocalSource, "https://same.test/cover.jpg", null)

        assertNotEquals(
            contentListSharedElementKey(first, "favorites"),
            contentListSharedElementKey(second, "favorites"),
        )
    }

    private fun listItem(
        uiId: Long,
        contentId: Long,
        source: ContentSource,
        coverUrl: String,
        trackingService: ScrobblerService?,
    ) = ContentCompactListModel(
        manga = Content(
            id = contentId,
            title = "Title",
            altTitles = emptySet(),
            url = "/$contentId",
            publicUrl = "https://example.test/$contentId",
            rating = 0f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        ),
        override = ContentOverride(coverUrl = coverUrl, title = null, contentRating = null),
        subtitle = null,
        counter = 0,
        id = uiId,
        metadataTrackingService = trackingService,
    )

    private object LocalSource : ContentSource {
        override val name: String = "LOCAL"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }

    private object TrackingSource : ContentSource {
        override val name: String = "TRACKING_ANILIST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}

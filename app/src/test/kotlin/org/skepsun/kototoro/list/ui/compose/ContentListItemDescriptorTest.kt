package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ContentListItemDescriptorTest {

    @Test
    fun `content rows use allocation-light id key and exact content type`() {
        val item = ContentCompactListModel(
            manga = content(id = 42L),
            override = null,
            subtitle = null,
            counter = 0,
        )

        val descriptor = contentListItemDescriptor(item, index = 7)

        assertEquals(42L, descriptor.key)
        assertEquals(ContentListItemType.COMPACT_CARD, descriptor.contentType)
    }

    @Test
    fun `supplementary rows keep distinct reusable content types`() {
        val descriptor = contentListItemDescriptor(ListHeader(123), index = 3)

        assertEquals(ContentListItemType.HEADER, descriptor.contentType)
    }

    @Test
    fun `combined index maps leading and paging regions without exceptions`() {
        val mapper = CombinedContentListIndex(leadingCount = 2, pagingCount = 3)

        assertEquals(ContentListItemOrigin.Leading(0), mapper.origin(0))
        assertEquals(ContentListItemOrigin.Leading(1), mapper.origin(1))
        assertEquals(ContentListItemOrigin.Paging(0), mapper.origin(2))
        assertEquals(ContentListItemOrigin.Paging(2), mapper.origin(4))
        assertEquals(ContentListItemOrigin.OutOfBounds, mapper.origin(5))
    }

    @Test
    fun `stale layout index is rejected when current paging snapshot shrinks`() {
        val mapper = CombinedContentListIndex(leadingCount = 0, pagingCount = 65)

        assertEquals(
            ContentListItemOrigin.OutOfBounds,
            mapper.origin(index = 64, availablePagingCount = 64),
        )
    }

    private fun content(id: Long) = Content(
        id = id,
        title = "Title",
        altTitles = emptySet(),
        url = "/$id",
        publicUrl = "https://example.test/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = TestSource,
    )

    private object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}

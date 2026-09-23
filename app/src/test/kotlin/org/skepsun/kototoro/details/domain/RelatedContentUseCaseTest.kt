package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class RelatedContentUseCaseTest {

    @Test
    fun `damaged seed excludes same Komiic work found through a mirror domain`() {
        val seed = content(
            id = -9220559807833554778L,
            title = "黑月的耶尔克纳赫特",
            url = "",
            publicUrl = "",
            coverUrl = "https://public.komiic.com/comics/work-id/cover.jpg?token=old",
        )
        val sameWork = content(
            id = -3838004012385354275L,
            title = seed.title,
            url = "4178",
            publicUrl = "https://komiic.cc/comic/4178",
            coverUrl = "https://public.komiic.cc/comics/work-id/cover.jpg?token=new",
        )
        val unrelated = content(
            id = 2L,
            title = "其他作品",
            url = "2",
            publicUrl = "https://komiic.cc/comic/2",
            coverUrl = "https://public.komiic.cc/comics/other/cover.jpg",
        )

        assertEquals(
            listOf(unrelated),
            filterCurrentWorkFromRelated(seed, listOf(sameWork, unrelated), emptySet()),
        )
    }

    @Test
    fun `entity projection key excludes current work even when metadata changed`() {
        val seed = content(
            id = 1L,
            title = "旧标题",
            url = "",
            publicUrl = "",
            coverUrl = null,
        )
        val sameWork = content(
            id = 2L,
            title = "新标题",
            url = "4178",
            publicUrl = "https://komiic.cc/comic/4178",
            coverUrl = "https://public.komiic.cc/comics/new/cover.jpg",
        )

        assertEquals(
            emptyList<Content>(),
            filterCurrentWorkFromRelated(seed, listOf(sameWork), setOf("url:4178")),
        )
    }

    @Test
    fun `matching title and cover from another source remains related`() {
        val seed = content(
            id = 1L,
            title = "同名作品",
            url = "",
            publicUrl = "",
            coverUrl = "https://public.komiic.com/comics/work-id/cover.jpg",
        )
        val otherSourceWork = content(
            id = 2L,
            title = seed.title,
            url = "/work/2",
            publicUrl = "https://example.test/work/2",
            coverUrl = "https://images.example.test/comics/work-id/cover.jpg",
            source = OtherSource,
        )

        assertEquals(
            listOf(otherSourceWork),
            filterCurrentWorkFromRelated(seed, listOf(otherSourceWork), emptySet()),
        )
    }

    private fun content(
        id: Long,
        title: String,
        url: String,
        publicUrl: String,
        coverUrl: String?,
        source: ContentSource = KomiicSource,
    ) = Content(
        id = id,
        title = title,
        altTitles = emptySet(),
        url = url,
        publicUrl = publicUrl,
        rating = 0f,
        contentRating = null,
        coverUrl = coverUrl,
        largeCoverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        description = null,
        chapters = null,
        source = source,
    )

    private data object KomiicSource : ContentSource {
        override val name = "KOMIIC"
        override val locale = "zh"
        override val contentType = ContentType.MANGA
    }

    private data object OtherSource : ContentSource {
        override val name = "OTHER"
        override val locale = "zh"
        override val contentType = ContentType.MANGA
    }
}

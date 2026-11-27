package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.FULLY_READ_CHAPTER_ID
import java.io.File

class ChaptersMapperReadStateTest {
    @Test
    fun `list and grid can dim through either the furthest or current chapter`() {
        for (type in listOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO)) {
            val details = details(type)
            for (grid in listOf(false, true)) {
                for (furthest in listOf(5L, 0L)) {
                    val later = details.map(current = 5, furthest = furthest, grid = grid)
                    assertEquals(listOf(6L), later.unreadIds())
                    assertEquals(5L, later.single { it.isCurrent }.chapter.id)
                }

                val preserved = details.map(current = 1, furthest = 5, grid = grid)
                assertEquals(listOf(6L), preserved.unreadIds())
                assertEquals(1L, preserved.single { it.isCurrent }.chapter.id)

                val earlier = details.map(current = 1, furthest = 0, grid = grid)
                assertEquals((2L..6L).toList(), earlier.unreadIds())
                assertEquals(1L, earlier.single { it.isCurrent }.chapter.id)
            }
        }
    }

    @Test
    fun `downloaded reading chapters use the same cutoff`() {
        val details = details(ContentType.MANGA, downloaded = true)
        assertEquals(listOf(6L), details.map(current = 5, furthest = 5, downloadedOnly = true).unreadIds())
        assertEquals(listOf(6L), details.map(current = 1, furthest = 5, downloadedOnly = true).unreadIds())
        assertEquals((2L..6L).toList(), details.map(current = 1, furthest = 0, downloadedOnly = true).unreadIds())
    }

    @Test
    fun `downloaded chapters with unknown order stay undimmed`() {
        val details = details(ContentType.MANGA, downloaded = true, numbered = false)
        val chapters = details.map(current = 2, furthest = 0, downloadedOnly = true)
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), chapters.unreadIds())
        assertEquals(2L, chapters.single { it.isCurrent }.chapter.id)
    }

    @Test
    fun `completed history stays dim in both modes`() {
        val details = details(ContentType.MANGA, downloaded = true)
        for (furthest in listOf(6L, 0L)) {
            val completed = details.map(current = FULLY_READ_CHAPTER_ID, furthest = furthest, downloadedOnly = true)
            assertEquals(6, completed.size)
            assertEquals(emptyList<Long>(), completed.unreadIds())
        }
    }

    private fun details(type: ContentType, downloaded: Boolean = false, numbered: Boolean = true): ContentDetails {
        val source = object : ContentSource {
            override val name = "test"
            override val locale = ""
            override val contentType = type
        }
        val chapters = (1L..6L).map { id ->
            ContentChapter(
                id = id,
                title = "Chapter $id",
                number = if (numbered) id.toFloat() else 0f,
                volume = 0,
                url = "/chapter/$id",
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
        val content = Content(
            id = 10L,
            title = "Test work",
            altTitles = emptySet(),
            url = "/work",
            publicUrl = "",
            rating = 0f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            chapters = chapters,
            source = source,
        )
        val local = if (downloaded) {
            LocalContent(
                manga = content.copy(chapters = chapters.map { it.copy(url = "zip:///work.cbz#${it.id}") }),
                file = File("work.cbz"),
            )
        } else {
            null
        }
        return ContentDetails(content, local, override = null, description = null, isLoaded = true)
    }

    private fun ContentDetails.map(
        current: Long,
        furthest: Long,
        grid: Boolean = false,
        downloadedOnly: Boolean = false,
    ) = mapChapters(
        currentChapterId = current,
        furthestChapterId = furthest,
        newCount = 0,
        branch = null,
        bookmarks = emptyList(),
        isGrid = grid,
        isDownloadedOnly = downloadedOnly,
    )

    private fun List<ChapterListItem>.unreadIds() = filter { it.isUnread }.map { it.chapter.id }
}

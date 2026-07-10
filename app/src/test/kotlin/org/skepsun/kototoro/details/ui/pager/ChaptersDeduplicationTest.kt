package org.skepsun.kototoro.details.ui.pager

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.ui.mapChapters
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.details.ui.TestContentSource
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.parsers.model.ContentChapter

class ChaptersDeduplicationTest : StringSpec({

    fun createChapter(
        id: Long,
        number: Float,
        title: String?,
        volume: Int = 0,
        scanlator: String? = null,
        branch: String? = null,
        uploadDate: Long = 0L
    ): ContentChapter {
        return ContentChapter(
            id = id,
            title = title,
            number = number,
            volume = volume,
            url = "http://example.com/chapter/$id",
            scanlator = scanlator,
            uploadDate = uploadDate,
            branch = branch,
            source = TestContentSource
        )
    }

    "should deduplicate chapters with same number and prioritize latest uploadDate" {
        val ch1 = createChapter(id = 1, number = 1.0f, title = "Chapter 1", uploadDate = 1000L, scanlator = "ScanA")
        val ch2 = createChapter(id = 2, number = 1.0f, title = "Chapter 1 - Special Ed", uploadDate = 2000L, scanlator = "ScanB") // Latest

        val items = listOf(
            ChapterListItem(ch1, flags = 0),
            ChapterListItem(ch2, flags = 0)
        )

        val merged = items.mergeRepeated()
        merged.size shouldBe 1
        merged.first().chapter.id shouldBe 2L
        merged.first().chapter.scanlator shouldBe "ScanB"
    }

    "should differentiate between chapters with different volumes even if same number" {
        val ch1 = createChapter(id = 1, number = 1.0f, title = "Chapter 1", volume = 1)
        val ch2 = createChapter(id = 2, number = 1.0f, title = "Chapter 1", volume = 2)

        val items = listOf(
            ChapterListItem(ch1, flags = 0),
            ChapterListItem(ch2, flags = 0)
        )

        val merged = items.mergeRepeated()
        merged.size shouldBe 2
        merged.map { it.chapter.id } shouldBe listOf(1L, 2L)
    }

    "should deduplicate unnumbered chapters based on name exact matching" {
        val ch1 = createChapter(id = 1, number = 0f, title = "Special Extra", uploadDate = 1000L)
        val ch2 = createChapter(id = 2, number = 0f, title = "Special Extra ", uploadDate = 2000L) // trimmed matches

        val items = listOf(
            ChapterListItem(ch1, flags = 0),
            ChapterListItem(ch2, flags = 0)
        )

        val merged = items.mergeRepeated()
        merged.size shouldBe 1
        merged.first().chapter.id shouldBe 2L
    }

    "should prioritize downloaded chapters if upload date is equal" {
        val ch1 = createChapter(id = 1, number = 2.0f, title = "Chapter 2", uploadDate = 1000L)
        val ch2 = createChapter(id = 2, number = 2.0f, title = "Chapter 2", uploadDate = 1000L)

        val items = listOf(
            ChapterListItem(ch1, flags = 0), // Not downloaded
            ChapterListItem(ch2, flags = ChapterListItem.FLAG_DOWNLOADED) // Downloaded
        )

        val merged = items.mergeRepeated()
        merged.size shouldBe 1
        merged.first().chapter.id shouldBe 2L
    }

    "should preserve original listing order for specials and fractional chapters" {
        val ch1 = createChapter(id = 1, number = 1.0f, title = "Chapter 1")
        val chSpecial = createChapter(id = 2, number = 0f, title = "Summer Special")
        val chFraction = createChapter(id = 3, number = 1.5f, title = "Chapter 1.5")
        val ch2 = createChapter(id = 4, number = 2.0f, title = "Chapter 2")

        val items = listOf(
            ChapterListItem(ch1, flags = 0),
            ChapterListItem(chSpecial, flags = 0),
            ChapterListItem(chFraction, flags = 0),
            ChapterListItem(ch2, flags = 0)
        )

        val merged = items.mergeRepeated()
        merged.map { it.chapter.id } shouldBe listOf(1L, 2L, 3L, 4L)
    }

    "should mark past chapters from other branches as read when currentChapterId is in another branch" {
        val ch1 = createChapter(id = 1, number = 1.0f, title = "Chapter 1", branch = "BranchA")
        val ch2 = createChapter(id = 2, number = 2.0f, title = "Chapter 2", branch = "BranchB")
        val ch3 = createChapter(id = 3, number = 3.0f, title = "Chapter 3", branch = "BranchB")

        val content = Content(
            id = 100L,
            title = "Manga",
            altTitles = emptySet(),
            url = "http://example.com/manga",
            publicUrl = "http://example.com/manga",
            rating = 0f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            chapters = listOf(ch1, ch2, ch3),
            source = TestContentSource
        )
        val contentDetails = ContentDetails(content)

        val mappedA = contentDetails.mapChapters(
            currentChapterId = 2L,
            newCount = 0,
            branch = "BranchA",
            bookmarks = emptyList(),
            isGrid = false,
            isDownloadedOnly = false,
            shareProgressAcrossBranches = true,
        )

        mappedA.size shouldBe 1
        mappedA.first().chapter.id shouldBe 1L
        mappedA.first().isUnread shouldBe false
    }

    "should keep branch progress isolated when repeated chapter merging is disabled" {
        val branchA = createChapter(id = 1, number = 1.0f, title = "Chapter 1", branch = "BranchA")
        val branchB = createChapter(id = 2, number = 2.0f, title = "Chapter 2", branch = "BranchB")
        val contentDetails = ContentDetails(
            Content(
                id = 100L,
                title = "Manga",
                altTitles = emptySet(),
                url = "http://example.com/manga",
                publicUrl = "http://example.com/manga",
                rating = 0f,
                contentRating = null,
                coverUrl = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                chapters = listOf(branchA, branchB),
                source = TestContentSource,
            ),
        )

        val mappedA = contentDetails.mapChapters(
            currentChapterId = branchB.id,
            newCount = 0,
            branch = "BranchA",
            bookmarks = emptyList(),
            isGrid = false,
            isDownloadedOnly = false,
        )

        mappedA.single().isUnread shouldBe true
    }

    "should compare volumes when sharing progress across branches" {
        val earlierVolume = createChapter(
            id = 1,
            number = 10.0f,
            title = "Volume 1 Chapter 10",
            volume = 1,
            branch = "BranchA",
        )
        val currentVolume = createChapter(
            id = 2,
            number = 1.0f,
            title = "Volume 2 Chapter 1",
            volume = 2,
            branch = "BranchB",
        )
        val contentDetails = ContentDetails(
            Content(
                id = 100L,
                title = "Manga",
                altTitles = emptySet(),
                url = "http://example.com/manga",
                publicUrl = "http://example.com/manga",
                rating = 0f,
                contentRating = null,
                coverUrl = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                chapters = listOf(earlierVolume, currentVolume),
                source = TestContentSource,
            ),
        )

        val mapped = contentDetails.mapChapters(
            currentChapterId = currentVolume.id,
            newCount = 0,
            branch = "BranchA",
            bookmarks = emptyList(),
            isGrid = false,
            isDownloadedOnly = false,
            shareProgressAcrossBranches = true,
        )

        mapped.single().isUnread shouldBe false
    }
})

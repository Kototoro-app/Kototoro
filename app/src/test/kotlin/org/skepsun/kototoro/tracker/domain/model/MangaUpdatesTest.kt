package org.skepsun.kototoro.tracker.domain.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

class MangaUpdatesTest : StringSpec({

    "update event retains work owner projection anchor and source scoped chapter keys" {
        val chapter = ContentChapter(
            id = 7L,
            title = "Chapter 7",
            number = 7f,
            volume = 0,
            url = "https://example.com/manga/chapter/7",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = TestContentSource,
        )
        val manga = Content(
            id = 999L,
            title = "Manga",
            altTitles = emptySet(),
            url = "https://example.com/manga",
            publicUrl = "https://example.com/manga",
            rating = 0f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            chapters = listOf(chapter),
            source = TestContentSource,
        )

        val updates = MangaUpdates.Success(
            manga = manga,
            entityId = 42L,
            anchorMangaId = 24L,
            branch = null,
            newChapters = listOf(chapter),
            isValid = true,
        )

        updates.entityId shouldBe 42L
        updates.anchorMangaId shouldBe 24L
        updates.newChapterKeys shouldBe listOf(
            SourceChapterKey(
                source = TestContentSource.name,
                key = chapter.url,
            ),
        )
    }
})

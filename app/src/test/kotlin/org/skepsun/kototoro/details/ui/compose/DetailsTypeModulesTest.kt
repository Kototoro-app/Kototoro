package org.skepsun.kototoro.details.ui.compose

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.details.ui.TestContentSource
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.parsers.model.ContentChapter

class DetailsTypeModulesTest : StringSpec({

    fun item(id: Long, current: Boolean = false) = ChapterListItem(
        ContentChapter(
            id = id,
            title = "Chapter $id",
            number = id.toFloat(),
            volume = 0,
            url = "http://example.com/chapter/$id",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = TestContentSource,
        ),
        flags = if (current) ChapterListItem.FLAG_CURRENT else 0,
    )

    fun List<NovelCatalogRow>.shape() = map { it.item.chapter.id to it.tag }

    "lists the current chapter, the next one and the latest" {
        val chapters = (1L..10L).map { item(it, current = it == 4L) }
        novelCatalogRows(chapters).shape() shouldBe listOf(
            4L to NovelCatalogTag.CONTINUE,
            5L to null,
            10L to NovelCatalogTag.LATEST,
        )
    }

    "starts from the first chapter before reading" {
        novelCatalogRows((1L..3L).map { item(it) }).shape() shouldBe listOf(1L to null, 3L to NovelCatalogTag.LATEST)
    }

    "does not repeat the latest chapter when the reader is on it" {
        val chapters = (1L..3L).map { item(it, current = it == 3L) }
        novelCatalogRows(chapters).shape() shouldBe listOf(3L to NovelCatalogTag.CONTINUE)
    }

    "the reader's rating prefers the community stars over a tracker score" {
        detailsMyRating(communityStars = 7, trackerRating = 0.9f) shouldBe 7
        detailsMyRating(communityStars = null, trackerRating = 0.8f) shouldBe 8
        detailsMyRating(communityStars = null, trackerRating = 0f) shouldBe null
    }
})

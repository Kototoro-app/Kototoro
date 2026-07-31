package org.skepsun.kototoro.video.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.details.ui.TestContentSource
import org.skepsun.kototoro.parsers.model.ContentChapter

class PlayerChapterGroupingTest : StringSpec({

    fun chapter(id: Long, branch: String?): ContentChapter = ContentChapter(
        id = id,
        title = "Chapter $id",
        number = id.toFloat(),
        volume = 0,
        url = "/chapter/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = branch,
        source = TestContentSource,
    )

    "groups chapters by branch while preserving group and chapter order" {
        val groups = groupPlayerChapters(
            listOf(
                chapter(1, "Season 2"),
                chapter(2, "Season 1"),
                chapter(3, "Season 2"),
                chapter(4, "Season 1"),
            ),
        )

        groups.map { it.name } shouldBe listOf("Season 2", "Season 1")
        groups.map { group -> group.chapters.map { it.id } } shouldBe listOf(
            listOf(1L, 3L),
            listOf(2L, 4L),
        )
    }

    "treats blank branch names as ungrouped" {
        val groups = groupPlayerChapters(
            listOf(
                chapter(1, null),
                chapter(2, "  "),
                chapter(3, "Season 1"),
            ),
        )

        groups.map { it.name } shouldBe listOf(null, "Season 1")
        groups.first().chapters.map { it.id } shouldBe listOf(1L, 2L)
    }

    "finds the page containing the current chapter" {
        val groups = groupPlayerChapters(
            listOf(
                chapter(1, "Season 1"),
                chapter(2, "Season 2"),
            ),
        )

        findPlayerChapterGroupIndex(groups, chapterId = 2L) shouldBe 1
        findPlayerChapterGroupIndex(groups, chapterId = 99L) shouldBe 0
    }
})

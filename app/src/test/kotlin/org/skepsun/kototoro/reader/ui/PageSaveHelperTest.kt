package org.skepsun.kototoro.reader.ui

import android.content.res.Resources
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class PageSaveHelperTest {

    private val resources = mockk<Resources> {
        every { getString(R.string.chapter_number, *anyVararg()) } answers {
            "Chapter ${(args[1] as Array<*>)[0]}"
        }
        every { getString(R.string.chapter_volume_number, *anyVararg()) } answers {
            val formatArgs = args[1] as Array<*>
            "Vol ${formatArgs[0]} Chapter ${formatArgs[1]}"
        }
        every { getString(R.string.reader_page_label, *anyVararg()) } answers {
            "Page ${(args[1] as Array<*>)[0]}"
        }
    }

    @Test
    fun `file name uses configured manga title length`() {
        val task = task(title = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

        task.getFileBaseName(resources, mangaTitleLength = 24).substringBefore(" - ") shouldBe
            "ABCDEFGHIJKLMNOPQRSTUVWX"
    }

    @Test
    fun `file name reads title, chapter and page`() {
        task(number = 105f).getFileBaseName(resources, mangaTitleLength = 24) shouldBe
            "Title - Chapter 105 - Page 3"
        task(number = 105.5f, volume = 2).getFileBaseName(resources, mangaTitleLength = 24) shouldBe
            "Title - Vol 2 Chapter 105.5 - Page 3"
    }

    @Test
    fun `chapter without a number falls back to its title or is left out`() {
        task(number = 0f, chapterTitle = "Extra: side/story").getFileBaseName(resources, 24) shouldBe
            "Title - Extra side story - Page 3"
        task(number = 0f).getFileBaseName(resources, 24) shouldBe "Title - Page 3"
    }

    private fun task(
        title: String = "Title",
        number: Float = 1f,
        volume: Int = 0,
        chapterTitle: String? = null,
    ) = PageSaveHelper.Task(
        manga = Content(
            id = 1L,
            title = title,
            altTitles = emptySet(),
            url = "manga",
            publicUrl = "https://example.com/manga",
            rating = 0f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            chapters = listOf(
                ContentChapter(
                    id = 2L,
                    title = chapterTitle,
                    number = number,
                    volume = volume,
                    url = "chapter",
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = TestSource,
                ),
            ),
            source = TestSource,
        ),
        chapterId = 2L,
        pageNumber = 3,
        page = ContentPage(
            id = 4L,
            url = "page.jpg",
            preview = null,
            source = TestSource,
        ),
    )

    private data object TestSource : ContentSource {
        override val name: String = "TEST"
        override val locale: String = "en"
        override val contentType: ContentType = ContentType.MANGA
    }
}

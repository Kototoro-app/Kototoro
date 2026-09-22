package org.skepsun.kototoro.core.parser

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.ChaptersDao
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ContentIntentResolutionTest {

    private val chaptersDao = mockk<ChaptersDao>()
    private val database = mockk<MangaDatabase> {
        every { getChaptersDao() } returns chaptersDao
    }
    private val repository = spyk(
        ContentDataRepository(
            db = database,
            resolverProvider = mockk(),
            appShortcutManagerProvider = mockk(),
            projectionIdentityResolver = mockk(),
        ),
    )

    @Test
    fun `feed reader card restores saved Komiic API id instead of only cached chapters`() = runTest {
        val saved = content(url = "12345", publicUrl = "https://komiic.cc/comic/12345")
        val card = saved.copy(url = "", publicUrl = "", description = null, sourceData = null)
        coEvery { repository.findContentById(saved.id, withChapters = true) } returns saved
        coEvery { chaptersDao.findAll(saved.id) } returns listOf(
            ChapterEntity(
                chapterId = 7L,
                mangaId = saved.id,
                title = "Chapter 1",
                number = 1f,
                volume = 0,
                url = "56789",
                scanlator = null,
                uploadDate = 0L,
                branch = "12345",
                source = "KOMIIC",
                index = 0,
            ),
        )

        val resolved = repository.resolveIntent(ContentIntent.of(card), withChapters = true)

        assertEquals("12345", resolved?.url, "Komiic comicById must receive the saved API id, not an empty string")
        assertSame(saved, resolved)
    }

    @Test
    fun `favourite card with whitespace urls restores the saved projection`() = runTest {
        val saved = content()
        val card = saved.copy(url = "  ", publicUrl = "\t")
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(saved, repository.resolveIntent(ContentIntent.of(card), withChapters = false))
    }

    @Test
    fun `id only intent restores the saved projection`() = runTest {
        val saved = content()
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(saved, repository.resolveIntent(ContentIntent.of(saved.id), withChapters = false))
    }

    @Test
    fun `search result with another remote identity keeps its own urls despite local id collision`() = runTest {
        val saved = content()
        val result = saved.copy(url = "67890", publicUrl = "https://komiic.cc/comic/67890")
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(result, repository.resolveIntent(ContentIntent.of(result), withChapters = false))
    }

    @Test
    fun `public url alone still distinguishes a different remote identity`() = runTest {
        val saved = content()
        val result = saved.copy(url = "", publicUrl = "https://komiic.cc/comic/67890")
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(result, repository.resolveIntent(ContentIntent.of(result), withChapters = false))
    }

    @Test
    fun `url alone still distinguishes a different remote identity`() = runTest {
        val saved = content()
        val result = saved.copy(url = "67890", publicUrl = "")
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(result, repository.resolveIntent(ContentIntent.of(result), withChapters = false))
    }

    @Test
    fun `url-less content from another source must not use a colliding saved projection`() = runTest {
        val saved = content()
        val result = saved.copy(url = "", publicUrl = "", source = source("OTHER"))
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(result, repository.resolveIntent(ContentIntent.of(result), withChapters = false))
    }

    @Test
    fun `matching remote identity still prefers saved details`() = runTest {
        val saved = content()
        coEvery { repository.findContentById(saved.id, withChapters = false) } returns saved

        assertSame(
            saved,
            repository.resolveIntent(ContentIntent.of(saved.copy(description = null)), withChapters = false),
        )
    }

    private fun content(
        url: String = "12345",
        publicUrl: String = "https://komiic.cc/comic/12345",
    ) = Content(
        id = 42L,
        title = "Saved comic",
        altTitles = emptySet(),
        url = url,
        publicUrl = publicUrl,
        rating = -1f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source("KOMIIC"),
        description = "Saved description",
        sourceData = "saved source data",
    )

    private fun source(name: String) = object : ContentSource {
        override val name = name
        override val locale = "zh"
        override val contentType = ContentType.MANGA
    }
}

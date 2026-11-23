package org.skepsun.kototoro.download.ui.worker

import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * #688: a translate job used to work off "the whole stored record or nothing", so a selection that mixes a chapter
 * already on disk with a chapter that is not failed as a whole with `No downloaded chapter to translate`.
 *
 * These tests drive [selectTranslationChapters] with the same inputs the worker feeds it: the stored record's own
 * chapters, the chapter list `resolvePreparationChapters` derives from them (matching by id, then number+branch, then
 * title) and the local copy.
 */
class TranslationChapterSelectionTest {

    @Test
    fun `mixed selection translates the downloaded chapter instead of failing the job`() = runTest {
        val first = remoteChapter(id = 1L)
        val second = remoteChapter(id = 2L)
        val stored = content(listOf(first, second))
        // Only chapter 1 was downloaded, so that is all the local copy knows about.
        val local = content(
            chapters = listOf(localChapter(id = 1L)),
            source = LocalMangaSource,
        )

        val selection = select(stored, requestedIds = setOf(1L, 2L), localCopy = local)

        assertEquals(listOf(1L), selection.chapters.map { it.value.id })
        assertEquals(local, selection.workContent)
    }

    @Test
    fun `selection with nothing on disk still fails with the original message`() = runTest {
        val stored = content(listOf(remoteChapter(id = 1L), remoteChapter(id = 2L)))
        val local = content(
            chapters = listOf(remoteChapter(id = 1L), remoteChapter(id = 2L)),
            source = LocalMangaSource,
        )

        val error = shouldThrow<IllegalStateException> {
            select(stored, requestedIds = setOf(1L, 2L), localCopy = local)
        }

        assertEquals("No downloaded chapter to translate for Test manga", error.message)
    }

    @Test
    fun `chapter matched by number and branch is translated even when its id changed`() = runTest {
        val first = remoteChapter(id = 1L, number = 1f)
        val second = remoteChapter(id = 2L, number = 2f)
        val stored = content(listOf(first, second))
        // The local copy was written from a refetched chapter list, so it holds different ids, and it does not know
        // chapter 2 at all because that one was never downloaded.
        val local = content(
            chapters = listOf(localChapter(id = 101L, number = 1f)),
            source = LocalMangaSource,
        )

        val selection = select(stored, requestedIds = setOf(1L, 2L), localCopy = local)

        assertEquals(listOf(101L), selection.chapters.map { it.value.id })
    }

    @Test
    fun `chapter matched by title is translated even when id and number disagree`() = runTest {
        val first = remoteChapter(id = 1L, number = 0f)
        val second = remoteChapter(id = 2L, number = 0f)
        val stored = content(listOf(first, second))
        val local = content(
            chapters = listOf(localChapter(id = 201L, number = 0f, title = "Chapter 1")),
            source = LocalMangaSource,
        )

        val selection = select(stored, requestedIds = setOf(1L, 2L), localCopy = local)

        assertEquals(listOf(201L), selection.chapters.map { it.value.id })
    }

    @Test
    fun `locally stored chapters keep the stored record as the content to translate`() = runTest {
        val stored = content(listOf(localChapter(id = 1L), localChapter(id = 2L)), source = LocalMangaSource)

        val selection = select(stored, requestedIds = setOf(1L), localCopy = null)

        assertEquals(listOf(1L), selection.chapters.map { it.value.id })
        assertEquals(stored, selection.workContent)
    }

    private suspend fun select(
        stored: Content,
        requestedIds: Set<Long>,
        localCopy: Content?,
    ): TranslationChapterSelection {
        val requested = requestedChapters(stored, requestedIds)
        return selectTranslationChapters(
            storedContent = stored,
            requestedChapters = requested,
            preparedChapters = { preparedChapters(requested, localCopy?.chapters.orEmpty()) },
            loadLocalCopy = { localCopy },
            isEligible = ::isDownloaded,
        )
    }

    /** Mirrors `DownloadWorker.getChapters`: the content's own chapters for the requested ids, all or nothing. */
    private fun requestedChapters(content: Content, requestedIds: Set<Long>): List<IndexedValue<ContentChapter>> {
        val chapters = content.chapters.orEmpty().filter { it.id in requestedIds }
        check(chapters.size == requestedIds.size) { "requested chapters not found in content" }
        return chapters.mapIndexed { index, chapter -> IndexedValue(index, chapter) }
    }

    /** Mirrors `DownloadWorker.resolvePreparationChapters` with the chapter matching it delegates to. */
    private fun preparedChapters(
        requested: List<IndexedValue<ContentChapter>>,
        localChapters: List<ContentChapter>,
    ): List<IndexedValue<ContentChapter>> = requested.map { item ->
        val chapter = item.value
        if (isDownloaded(chapter)) {
            return@map item
        }
        val local = matchLocalChapter(chapter, localChapters)
        if (local != null) IndexedValue(item.index, local) else item
    }

    private fun isDownloaded(chapter: ContentChapter): Boolean {
        return chapter.source.isLocal || chapter.url.startsWith("file:")
    }

    private fun remoteChapter(
        id: Long,
        number: Float = id.toFloat(),
        title: String = "Chapter $id",
    ) = ContentChapter(
        id = id,
        title = title,
        number = number,
        volume = 0,
        url = "/chapter/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = TestSource,
    )

    private fun localChapter(
        id: Long,
        number: Float = id.toFloat(),
        title: String = "Chapter $id",
    ) = ContentChapter(
        id = id,
        title = title,
        number = number,
        volume = 0,
        url = "file:///storage/emulated/0/Kototoro/Test manga/chapter_$id.cbz",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = LocalMangaSource,
    )

    private fun content(
        chapters: List<ContentChapter>,
        source: ContentSource = TestSource,
    ) = Content(
        id = 42L,
        title = "Test manga",
        altTitles = emptySet(),
        url = "/manga/42",
        publicUrl = "https://example.com/manga/42",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        chapters = chapters,
        source = source,
    )

    private data object TestSource : ContentSource {
        override val name = "TEST"
        override val locale = "en"
        override val contentType = ContentType.MANGA
    }
}

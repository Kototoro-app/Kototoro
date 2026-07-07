package org.skepsun.kototoro.local.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.time.Instant
import org.skepsun.kototoro.core.db.MangaDatabase

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File

class DeleteReadChaptersUseCaseTest {

	private val localContentRepository = mockk<LocalMangaRepository>(relaxed = true)
	private val historyRepository = mockk<HistoryRepository>(relaxed = true)
	private val mangaRepositoryFactory = mockk<ContentRepository.Factory>(relaxed = true)
	private val db = mockk<MangaDatabase>(relaxed = true)

	private val useCase = DeleteReadChaptersUseCase(
		localContentRepository,
		historyRepository,
		mangaRepositoryFactory,
		db
	)

	@BeforeEach
	fun setUp() {
		mockkStatic(Uri::class)
		val mockUri = mockk<Uri>()
		every { Uri.parse(any()) } returns mockUri
		every { mockUri.scheme } returns "file"
		every { mockUri.path } returns "/tmp/manga"
		coEvery { db.getChaptersDao().findAll(any()) } returns emptyList()
		coEvery { localContentRepository.getRemoteContent(any()) } returns null
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(Uri::class)
	}

	private val source = LocalMangaSource

	private fun createChapter(id: Long, number: Float, branch: String? = null): ContentChapter {
		return ContentChapter(
			id = id,
			title = "Chapter $number",
			number = number,
			volume = 0,
			url = "file:///tmp/manga/chapter/$id",
			scanlator = null,
			uploadDate = 0L,
			branch = branch,
			source = source
		)
	}

	private fun createContent(id: Long, chapters: List<ContentChapter>): Content {
		return Content(
			id = id,
			title = "Manga $id",
			altTitles = emptySet(),
			url = "file:///tmp/manga/$id",
			publicUrl = "file:///tmp/manga/$id",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			chapters = chapters,
			source = source
		)
	}

	@Test
	fun `local chapters containing history chapter ID bypasses network call and deletes correctly`() = runTest {
		// Arrange: 3 chapters (chronological order)
		val chapters = listOf(
			createChapter(1L, 1f),
			createChapter(2L, 2f),
			createChapter(3L, 3f)
		)
		val manga = createContent(100L, chapters)

		coEvery { historyRepository.getOne(manga) } returns ContentHistory(
			createdAt = Instant.now(),
			updatedAt = Instant.now(),
			chapterId = 2L, // read up to chapter 2
			page = 0,
			scroll = 0,
			percent = 1f,
			chaptersCount = 3
		)
		coEvery { localContentRepository.getList(0, null, null) } returns listOf(manga)

		// Act
		val deletedCount = useCase.invoke()

		// Assert: should delete chapter 1, keep 2 and 3
		assertEquals(1, deletedCount)
		coVerify { localContentRepository.deleteChapters(manga, setOf(1L)) }
		coVerify(exactly = 0) { mangaRepositoryFactory.create(any()) }
	}

	@Test
	fun `chapters are sorted chronologically before deletion to prevent deleting newer unread chapters`() = runTest {
		// Arrange: raw chapters in reverse order (newest first)
		val chapters = listOf(
			createChapter(3L, 3f),
			createChapter(2L, 2f),
			createChapter(1L, 1f)
		)
		val manga = createContent(100L, chapters)

		coEvery { historyRepository.getOne(manga) } returns ContentHistory(
			createdAt = Instant.now(),
			updatedAt = Instant.now(),
			chapterId = 2L, // read up to chapter 2
			page = 0,
			scroll = 0,
			percent = 1f,
			chaptersCount = 3
		)
		coEvery { localContentRepository.getList(0, null, null) } returns listOf(manga)

		// Act
		val deletedCount = useCase.invoke()

		// Assert: after sorting chronologically, list is [1, 2, 3]. History is 2.
		// So we take chapters before 2 (which is 1). It must delete 1, NOT 3!
		assertEquals(1, deletedCount)
		coVerify { localContentRepository.deleteChapters(manga, setOf(1L)) }
		coVerify(exactly = 0) { localContentRepository.deleteChapters(manga, setOf(3L)) }
	}

	@Test
	fun `history chapter not downloaded but in database deletes successfully`() = runTest {
		// Arrange: 3 chapters (chronological order)
		val localChapters = listOf(
			createChapter(1L, 1f),
			createChapter(2L, 2f)
		)
		// Chapter 3 is not downloaded (online) and is the current history chapter
		val dbChapters = listOf(
			createChapter(1L, 1f),
			createChapter(2L, 2f),
			createChapter(3L, 3f)
		).map {
			org.skepsun.kototoro.core.db.entity.ChapterEntity(
				chapterId = it.id,
				mangaId = 100L,
				title = it.title.orEmpty(),
				number = it.number,
				volume = it.volume,
				url = it.url,
				scanlator = it.scanlator,
				uploadDate = it.uploadDate,
				branch = it.branch,
				source = it.source.name,
				index = 0
			)
		}
		val manga = createContent(100L, localChapters)

		coEvery { historyRepository.getOne(manga) } returns ContentHistory(
			createdAt = Instant.now(),
			updatedAt = Instant.now(),
			chapterId = 3L, // read up to chapter 3 (online)
			page = 0,
			scroll = 0,
			percent = 1f,
			chaptersCount = 3
		)
		coEvery { db.getChaptersDao().findAll(100L) } returns dbChapters
		coEvery { localContentRepository.getList(0, null, null) } returns listOf(manga)

		// Act
		val deletedCount = useCase.invoke()

		// Assert: should delete chapter 1 and 2, which are the downloaded ones before history chapter 3
		assertEquals(2, deletedCount)
		coVerify { localContentRepository.deleteChapters(manga, setOf(1L, 2L)) }
	}

	@Test
	fun `history chapter in database for remote downloaded manga deletes successfully`() = runTest {
		val remoteSource = mockk<org.skepsun.kototoro.parsers.model.ContentSource>()
		every { remoteSource.name } returns "RemoteSource"
		every { remoteSource.contentType } returns org.skepsun.kototoro.parsers.model.ContentType.MANGA

		val localChapters = listOf(
			ContentChapter(
				id = 1L,
				title = "Chapter 1",
				number = 1f,
				volume = 0,
				url = "file:///tmp/manga/chapter/1",
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = remoteSource
			),
			ContentChapter(
				id = 2L,
				title = "Chapter 2",
				number = 2f,
				volume = 0,
				url = "file:///tmp/manga/chapter/2",
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = remoteSource
			)
		)
		val manga = Content(
			id = 100L,
			title = "Remote Manga 100",
			altTitles = emptySet(),
			url = "/manga/100",
			publicUrl = "/manga/100",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			chapters = localChapters,
			source = remoteSource
		)
		val localContent = org.skepsun.kototoro.local.domain.model.LocalContent(
			manga = manga,
			file = File("/tmp/manga/100")
		)

		coEvery { historyRepository.getOne(manga) } returns ContentHistory(
			createdAt = Instant.now(),
			updatedAt = Instant.now(),
			chapterId = 2L, // read up to chapter 2
			page = 0,
			scroll = 0,
			percent = 1f,
			chaptersCount = 2
		)
		coEvery { localContentRepository.getList(0, null, null) } returns listOf(manga)
		coEvery { localContentRepository.findSavedContent(manga) } returns localContent

		// Act
		val deletedCount = useCase.invoke()

		// Assert: should delete chapter 1
		assertEquals(1, deletedCount)
		coVerify { localContentRepository.deleteChapters(manga, setOf(1L)) }
	}
}

package org.skepsun.kototoro.settings.backup

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.AppBackupAgent
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentTag
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppBackupAgentTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var favouritesRepository: FavouritesRepository

	@Inject
	lateinit var backupRepository: BackupRepository

	@Inject
	lateinit var database: MangaDatabase

	// Self-contained fixture for the current Content model. The legacy koitharu
	// Manga sample fixtures (SampleData) were retired with the Manga -> Content
	// migration, so the test builds its own Content instead.
	private val tag = ContentTag(title = "Adventure", key = "adventure", source = TestContentSource)

	private val chapter = ContentChapter(
		id = 1L,
		title = "Chapter 1",
		number = 1f,
		volume = 1,
		url = "/chapter/1",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = TestContentSource,
	)

	private val content = Content(
		id = 123456789L,
		title = "Test Content",
		altTitles = emptySet(),
		url = "/manga/test",
		publicUrl = "https://test.example/manga",
		rating = 0.5f,
		contentRating = null,
		coverUrl = null,
		tags = setOf(tag),
		state = null,
		authors = emptySet(),
		largeCoverUrl = null,
		description = null,
		chapters = listOf(chapter),
		source = TestContentSource,
	)

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
	}

	@Test
	fun backupAndRestore() = runTest {
		val category = favouritesRepository.createCategory(
			title = "Test Category",
			sortOrder = ListSortOrder.NEWEST,
			isTrackerEnabled = false,
			isVisibleOnShelf = true,
		)
		favouritesRepository.addToCategory(categoryId = category.id, mangas = listOf(content))
		historyRepository.addOrUpdate(
			manga = content,
			chapterId = chapter.id,
			page = 3,
			scroll = 40,
			percent = 0.2f,
			force = false,
		)
		val history = checkNotNull(historyRepository.getOne(content))

		val agent = AppBackupAgent()
		val backup = agent.createBackupFile(
			context = InstrumentationRegistry.getInstrumentation().targetContext,
			repository = backupRepository,
		)

		database.clearAllTables()
		assertTrue(favouritesRepository.getAllContent().isEmpty())
		assertNull(historyRepository.getLastOrNull())

		backup.inputStream().use {
			agent.restoreBackupFile(it.fd, backup.length(), backupRepository)
		}

		assertEquals(category, favouritesRepository.getCategory(category.id))
		assertEquals(history, historyRepository.getOne(content))
		assertEquals(listOf(content), favouritesRepository.getContent(category.id))

		val allTags = database.getTagsDao().findTags(TestContentSource.name).toContentTags()
		assertTrue(tag in allTags)
	}

	@Test
	fun restoreOldBackup() {
		val agent = AppBackupAgent()
		val backup = File.createTempFile("backup_", ".tmp")
		InstrumentationRegistry.getInstrumentation().context.assets
			.open("kotatsu_test.bak", AssetManager.ACCESS_STREAMING)
			.use { input ->
				backup.outputStream().use { output ->
					input.copyTo(output)
				}
			}
		backup.inputStream().use {
			agent.restoreBackupFile(it.fd, backup.length(), backupRepository)
		}
		runTest {
			assertEquals(6, historyRepository.observeAll().first().size)
			assertEquals(2, favouritesRepository.observeCategories().first().size)
			assertEquals(15, favouritesRepository.getAllContent().size)
		}
	}
}

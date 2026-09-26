package org.skepsun.kototoro.backups.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.backups.data.model.WorkFavouriteBackup
import org.skepsun.kototoro.backups.data.model.WorkHistoryBackup
import org.skepsun.kototoro.backups.domain.AppBackupAgent
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.RestoreCheckpointEntity
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentTag
import java.io.File
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * 验证 §6.6 恢复 checkpoint：
 * - SNAPSHOT_REPLACE 改为逐节「先清后写」：中途失败只波及当前节，已完成节保留，
 *   未处理节不会在恢复前被清空（旧的「整体先清库」会在崩溃时造成数据全失）。
 * - 相同 restore_id 重试可断点续传（已在 checkpoint 中完成的节跳过，映射快照恢复）。
 * - 成功后 checkpoint 被清理；mode/节集合不匹配的 checkpoint 视为全新恢复。
 *
 * 以及投影优先下对旧（v3 WORK_*）备份的兼容：状态按 anchor_manga_id 落到投影上。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RestoreCheckpointTest {

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

	private companion object {
		const val CHECKPOINT_ID = "restore:test-session"
		const val LEGACY_ENTITY_ID = 42L
	}

	private val json = Json { ignoreUnknownKeys = true }
	private val idCounter = AtomicLong(1_000_000L)

	private val tag = ContentTag(title = "Adventure", key = "adventure", source = TestContentSource)

	private fun newChapter(id: Long, url: String) = ContentChapter(
		id = id,
		title = "Chapter $id",
		number = 1f,
		volume = 1,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = TestContentSource,
	)

	private fun newContent(title: String): Content {
		val id = idCounter.incrementAndGet()
		return Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = "/manga/$title",
			publicUrl = "https://test.example/manga/$title",
			rating = 0.5f,
			contentRating = null,
			coverUrl = null,
			tags = setOf(tag),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = listOf(newChapter(1L, "/chapter/$title/1")),
			source = TestContentSource,
		)
	}

	@Before
	fun setUp() {
		hiltRule.inject()
		runBlocking {
			database.clearAllTables()
			database.getRestoreCheckpointDao().clearAll()
		}
	}

	@After
	fun tearDown() {
		runBlocking {
			database.getRestoreCheckpointDao().clearAll()
		}
	}

	private fun restoreSections(): Set<BackupSection> {
		val sections = EnumSet.allOf(BackupSection::class.java)
		sections.remove(BackupSection.SETTINGS)
		sections.remove(BackupSection.SETTINGS_READER_GRID)
		return sections
	}

	private suspend fun runRestore(
		backup: File,
		sections: Set<BackupSection>,
		mode: BackupRepository.RestoreMode,
		checkpointId: String?,
	): BackupRepository.RestoreBackupResult {
		return backup.inputStream().use { input ->
			ZipInputStream(input).use { zip ->
				backupRepository.restoreBackup(
					input = zip,
					sections = sections,
					progress = null,
					restoreMode = mode,
					checkpointId = checkpointId,
				)
			}
		}
	}

	/** Copies [backup], replacing the payload of the given sections. */
	private fun rewriteBackup(backup: File, replacements: Map<BackupSection, String>): File {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val out = File.createTempFile("rewritten_backup_", ".zip", context.cacheDir)
		val byEntryName = replacements.mapKeys { it.key.entryName }
		ZipOutputStream(out.outputStream()).use { zos ->
			ZipInputStream(backup.inputStream()).use { zis ->
				var entry = zis.nextEntry
				while (entry != null) {
					val bytes = zis.readBytes()
					zos.putNextEntry(ZipEntry(entry.name))
					zos.write(byEntryName[entry.name]?.toByteArray() ?: bytes)
					zos.closeEntry()
					entry = zis.nextEntry
				}
			}
		}
		return out
	}

	private fun withPoisonedScrobbling(backup: File): File {
		// 用畸形 JSON 制造确定性中途失败：readJsonArray 惰性解码为数组时在该节抛错
		// （逃逸 restoreToDb 逐行吞错）。SCROBBLING 不是延迟节且排在 SOURCES 之后，
		// 所以 SOURCES 已完成、HISTORY 等延迟节从未开始。
		return rewriteBackup(backup, mapOf(BackupSection.SCROBBLING to "[ {\"id\" : 1"))
	}

	private suspend fun seedBackupData(): Pair<Content, String> {
		val content = newContent("Test Content")
		val category = favouritesRepository.createCategory(
			title = "Test Category",
			sortOrder = ListSortOrder.NEWEST,
			isTrackerEnabled = false,
			isVisibleOnShelf = true,
		)
		favouritesRepository.addToCategory(categoryId = category.id, mangas = listOf(content))
		historyRepository.addOrUpdate(
			manga = content,
			chapterId = content.chapters!!.first().id,
			page = 3,
			scroll = 40,
			percent = 0.2f,
			force = false,
		)
		return content to "Test Category"
	}

	@Test
	fun snapshotReplaceFailureKeepsUnprocessedDataThenResumes() = runTest {
		val (backupContent, _) = seedBackupData()
		val agent = AppBackupAgent()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = agent.createBackupFile(context, backupRepository)
		database.clearAllTables()
		database.getRestoreCheckpointDao().clearAll()

		// 预先存在的本地数据：恢复被中途打断时必须保留（逐节清空的关键安全属性）。
		val preExisting = newContent("PreExisting Content")
		historyRepository.addOrUpdate(
			manga = preExisting,
			chapterId = preExisting.chapters!!.first().id,
			page = 1,
			scroll = 0,
			percent = 0.1f,
			force = false,
		)
		assertNotNull(historyRepository.getOne(preExisting))

		val sections = restoreSections()
		val poisoned = withPoisonedScrobbling(backup)

		// 毒化备份触发中途失败（SCROBBLING 处抛错）。
		val failure = runCatching {
			runRestore(poisoned, sections, BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)
		}
		assertTrue("restore must fail on poisoned scrobbling", failure.isFailure)

		// 崩溃安全：HISTORY 是延迟节，从未开始 → 预置数据未被清空。
		assertNotNull(
			"pre-existing history must survive interrupted snapshot restore",
			historyRepository.getOne(preExisting),
		)

		// checkpoint 保留：已完成的节（含 SOURCES）记录在案，未完成的 HISTORY / 失败节未记录。
		val checkpoint = database.getRestoreCheckpointDao().findById(CHECKPOINT_ID)
		assertNotNull("checkpoint must persist after failure", checkpoint)
		val doneNames = json.decodeFromString<List<String>>(checkpoint!!.doneJson)
		assertTrue(BackupSection.SOURCES.name in doneNames)
		assertTrue(BackupSection.HISTORY.name !in doneNames)
		assertTrue(BackupSection.SCROBBLING.name !in doneNames)

		// 断点续传：同一 restore_id + 干净备份 → 跳过已完成节，补完剩余节。
		val resumed = runRestore(backup, sections, BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)
		assertTrue("must report resumed sections (got " + resumed.resumedSections + ")", resumed.resumedSections > 0)

		// 成功：checkpoint 被清理。
		assertNotNull(
			"resume must be complete",
			historyRepository.getOne(backupContent),
		)
		val followUpBackup = agent.createBackupFile(context, backupRepository)
		assertTrue("a successful restore must remain exportable", followUpBackup.length() > 0L)
		assertTrue("follow-up backup cleanup", followUpBackup.delete())
		assertTrue(
			"checkpoint must be deleted after successful restore",
			database.getRestoreCheckpointDao().findById(CHECKPOINT_ID) == null,
		)
		// createBackupFile reuses one path, so the follow-up delete may already have removed it.
		poisoned.delete()
		backup.delete()
	}

	@Test
	fun successfulRestoreClearsCheckpointAndIgnoresMismatchedOne() = runTest {
		val (backupContent, _) = seedBackupData()
		val agent = AppBackupAgent()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = agent.createBackupFile(context, backupRepository)
		database.clearAllTables()
		database.getRestoreCheckpointDao().clearAll()

		val sections = restoreSections()

		// 全新成功恢复：resumedSections == 0，checkpoint 删除。
		val first = runRestore(backup, sections, BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)
		assertEquals(0, first.resumedSections)
		assertNotNull(historyRepository.getOne(backupContent))
		assertTrue(database.getRestoreCheckpointDao().findById(CHECKPOINT_ID) == null)

		// 造一个 mode 不匹配的 checkpoint：必须被当作全新恢复，不跳节。
		database.getRestoreCheckpointDao().upsert(
			RestoreCheckpointEntity(
				id = CHECKPOINT_ID,
				mode = BackupRepository.RestoreMode.MERGE.name,
				sectionsJson = json.encodeToString(sections.map { it.name }),
				doneJson = json.encodeToString(listOf(BackupSection.SOURCES.name)),
				mappingJson = null,
				updatedAt = System.currentTimeMillis(),
			),
		)
		val second = runRestore(backup, sections, BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)
		assertEquals(0, second.resumedSections)
		assertTrue(database.getRestoreCheckpointDao().findById(CHECKPOINT_ID) == null)
	}

	@Test
	fun snapshotReplaceRestoreKeepsFavouriteCategories() = runTest {
		// Regression: §6.6 “逐节先清后写” 重构后，FAVOURITES（延迟节）在清表时
		// 误删了 favourite_categories —— CATEGORIES 节先恢复的分组被随后（payload
		// 为空的）FAVOURITES 节 deleteAll 清光，导致恢复后只剩「全部收藏」。
		// 分组表必须只由 CATEGORIES 节自己负责清理；这里断言恢复后分组及引用都在。
		val (_, categoryTitle) = seedBackupData()
		val agent = AppBackupAgent()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = agent.createBackupFile(context, backupRepository)
		database.clearAllTables()
		database.getRestoreCheckpointDao().clearAll()

		runRestore(backup, restoreSections(), BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)

		val categories = database.getFavouriteCategoriesDao().findAll()
		assertTrue(
			"restored categories must survive (got " + categories.map { it.title } + ")",
			categories.any { it.title == categoryTitle },
		)
		val restoredCategoryId = categories.first { it.title == categoryTitle }.categoryId.toLong()
		val favs = database.getFavouritesDao().findAllActiveEntries()
		assertTrue(
			"restored favourites must reference the restored category (got " + favs.map { it.categoryId } + ")",
			favs.isNotEmpty() && favs.all { it.categoryId == restoredCategoryId },
		)
	}

	@Test
	fun legacyWorkSectionsRestoreOntoAnchorProjections() = runTest {
		// v3 时代的备份把用户状态写在 WORK_* 节（按 entity 归属、带 anchor_manga_id）。
		// 投影优先下这些行必须按 anchor 落到 history / favourites，entity_id 只是载荷。
		val (content, categoryTitle) = seedBackupData()
		val categoryId = database.getFavouriteCategoriesDao().findAll()
			.first { it.title == categoryTitle }.categoryId.toLong()
		val agent = AppBackupAgent()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = agent.createBackupFile(context, backupRepository)
		val legacy = rewriteBackup(
			backup,
			mapOf(
				BackupSection.HISTORY to "[]",
				BackupSection.FAVOURITES to "[]",
				BackupSection.WORK_HISTORY to json.encodeToString(
					listOf(workHistory(anchorMangaId = content.id, updatedAt = 5_000L, percent = 0.6f)),
				),
				BackupSection.WORK_FAVOURITES to json.encodeToString(
					listOf(
						WorkFavouriteBackup(
							entityId = LEGACY_ENTITY_ID,
							categoryId = categoryId,
							anchorMangaId = content.id,
							createdAt = 4_000L,
							updatedAt = 4_000L,
						),
					),
				),
			),
		)
		database.clearAllTables()
		database.getRestoreCheckpointDao().clearAll()

		runRestore(legacy, restoreSections(), BackupRepository.RestoreMode.SNAPSHOT_REPLACE, null)

		val history = historyRepository.getOne(content)
		assertNotNull("legacy work history must land on its anchor projection", history)
		assertEquals(0.6f, history!!.percent)
		val favourites = database.getFavouritesDao().findAllActiveEntries()
		assertEquals(listOf(content.id), favourites.map { it.mangaId })
		assertTrue(legacy.delete())
		assertTrue(backup.delete())
	}

	@Test
	fun legacyWorkHistoryTombstoneDeletesOlderLocalHistoryOnMerge() = runTest {
		// 旧备份的 WORK_HISTORY 会携带删除墓碑；MERGE 时较新的墓碑必须保持删除，
		// 不能被“活跃写入”语义复活成正在阅读。
		val (content, _) = seedBackupData()
		val local = database.getHistoryDao().find(content.id)!!
		val agent = AppBackupAgent()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = agent.createBackupFile(context, backupRepository)
		val deletedAt = local.updatedAt + 1_000L
		val legacy = rewriteBackup(
			backup,
			mapOf(
				BackupSection.HISTORY to "[]",
				BackupSection.WORK_HISTORY to json.encodeToString(
					listOf(
						workHistory(
							anchorMangaId = content.id,
							updatedAt = deletedAt,
							percent = local.percent,
							deletedAt = deletedAt,
						),
					),
				),
			),
		)

		runRestore(legacy, restoreSections(), BackupRepository.RestoreMode.MERGE, null)

		assertNull("a newer legacy tombstone must win on merge", historyRepository.getOne(content))
		assertTrue(legacy.delete())
		assertTrue(backup.delete())
	}

	private fun workHistory(
		anchorMangaId: Long,
		updatedAt: Long,
		percent: Float,
		deletedAt: Long = 0L,
	) = WorkHistoryBackup(
		entityId = LEGACY_ENTITY_ID,
		anchorMangaId = anchorMangaId,
		createdAt = 1_000L,
		updatedAt = updatedAt,
		chapterId = 1L,
		page = 0,
		scroll = 0f,
		percent = percent,
		deletedAt = deletedAt,
		chaptersCount = 1,
	)
}

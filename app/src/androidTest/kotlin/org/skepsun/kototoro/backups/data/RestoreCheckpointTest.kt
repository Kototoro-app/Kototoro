package org.skepsun.kototoro.backups.data

import android.content.Context
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.backups.domain.AppBackupAgent
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.RestoreCheckpointEntity
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.history.data.WorkHistoryEntity
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

	private fun withPoisonedEntityEntities(backup: File): File {
		// 用畸形 JSON 制造确定性中途失败：readJsonArray 惰性解码为数组时
		// 在该节抛错（逃逸 restoreToDb 逐行吞错），顺序遍历恰好在
		// ENTITY_GRAPH_ENTITIES 处中止，延迟节从未开始。
		val poison = "[ {\"id\" : 1"
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val out = File.createTempFile("poisoned_backup_", ".zip", context.cacheDir)
		ZipOutputStream(out.outputStream()).use { zos ->
			ZipInputStream(backup.inputStream()).use { zis ->
				var entry = zis.nextEntry
				while (entry != null) {
					val bytes = zis.readBytes()
					zos.putNextEntry(ZipEntry(entry.name))
					zos.write(if (entry.name == BackupSection.ENTITY_GRAPH_ENTITIES.entryName) {
						poison.toByteArray()
					} else {
						bytes
					})
					zos.closeEntry()
					entry = zis.nextEntry
				}
			}
		}
		return out
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
		// 直接表级种子（WORK 实体 + work_history），绕开 repo 层对实体/绑定脚手架的依赖。
		val preEntityId = 7_777_777L
		database.getEntityGraphDao().upsertEntityRecord(
			EntityRecord(
				id = preEntityId,
				type = EntityType.WORK.name,
				contentType = "MANGA",
				syncId = "pre-existing-sync",
				primaryName = "PreExisting Work",
				nameHash = 42L,
				aliases = "[]",
				createdAt = 0L,
				lastAccessed = 0L,
				accessCount = 1,
			),
		)
		database.getWorkHistoryDao().upsert(
			WorkHistoryEntity(
				entityId = preEntityId,
				anchorMangaId = preEntityId,
				createdAt = 0L,
				updatedAt = 0L,
				chapterId = 1L,
				page = 1,
				scroll = 0f,
				percent = 0.1f,
				deletedAt = 0L,
				chaptersCount = 1,
			),
		)
		assertNotNull(database.getWorkHistoryDao().find(preEntityId))

		val sections = restoreSections()
		val poisoned = withPoisonedEntityEntities(backup)

		// 毒化备份触发中途失败（ENTITY_GRAPH_ENTITIES 处抛错）。
		val failure = runCatching {
			runRestore(poisoned, sections, BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)
		}
		assertTrue("restore must fail on poisoned entities", failure.isFailure)

		// 崩溃安全：HISTORY/WORK_HISTORY 是延迟节，从未开始 → 预置数据未被清空。
		assertNotNull(
			"pre-existing work history must survive interrupted snapshot restore",
			database.getWorkHistoryDao().find(preEntityId),
		)

		// checkpoint 保留：已完成的节（含 SOURCES）记录在案，未完成的 HISTORY/实体未记录。
		val checkpoint = database.getRestoreCheckpointDao().findById(CHECKPOINT_ID)
		assertNotNull("checkpoint must persist after failure", checkpoint)
		val doneNames = json.decodeFromString<List<String>>(checkpoint!!.doneJson)
		assertTrue(BackupSection.SOURCES.name in doneNames)
		assertTrue(BackupSection.HISTORY.name !in doneNames)
		assertTrue(BackupSection.ENTITY_GRAPH_ENTITIES.name !in doneNames)

		// 断点续传：同一 restore_id + 干净备份 → 跳过已完成节，补完剩余节。
		val resumed = runRestore(backup, sections, BackupRepository.RestoreMode.SNAPSHOT_REPLACE, CHECKPOINT_ID)
		assertTrue("must report resumed sections (got " + resumed.resumedSections + ")", resumed.resumedSections > 0)

		// 成功：checkpoint 被清理。
		assertNotNull(
			"resume must be complete",
			historyRepository.getOne(backupContent),
		)
		assertTrue(
			"entity graph must be restored after resume",
			database.getEntityGraphDao().dumpEntities().isNotEmpty(),
		)
		assertTrue(
			"checkpoint must be deleted after successful restore",
			database.getRestoreCheckpointDao().findById(CHECKPOINT_ID) == null,
		)
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
}

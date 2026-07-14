package org.skepsun.kototoro.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase

@RunWith(AndroidJUnit4::class)
class TracksDaoTest {

	private lateinit var db: MangaDatabase

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext(),
			MangaDatabase::class.java,
		).allowMainThreadQueries().build()
		db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun unreadWorkCountDeduplicatesEntitiesAcrossTracksAndLogs() = runTest {
		insertTrack(ownerId = 10L, mangaId = 100L, entityId = 10L, newChapters = 2, checkedAt = 200L)
		insertLog(mangaId = 100L, entityId = 10L, createdAt = 210L, unread = true)
		insertLog(mangaId = 101L, entityId = 10L, createdAt = 220L, unread = true)
		insertLog(mangaId = 200L, entityId = 20L, createdAt = 230L, unread = true)

		db.getTracksDao().observeUnreadWorkCount(lastOpenTime = 100L).first() shouldBe 2
	}

	@Test
	fun unreadWorkCountExcludesLegacyReadAndOldRows() = runTest {
		insertTrack(ownerId = -100L, mangaId = 100L, entityId = null, newChapters = 3, checkedAt = 300L)
		insertTrack(ownerId = 20L, mangaId = 200L, entityId = 20L, newChapters = 1, checkedAt = 100L)
		insertLog(mangaId = 300L, entityId = 30L, createdAt = 300L, unread = false)
		insertLog(mangaId = 400L, entityId = null, createdAt = 300L, unread = true)

		db.getTracksDao().observeUnreadWorkCount(lastOpenTime = 100L).first() shouldBe 0
	}

	@Test
	fun insertTracksFromUnreadLogsSkipsOrphanProjection() = runTest {
		insertLog(mangaId = 100L, entityId = null, createdAt = 300L, unread = true)
		enableForeignKeys()

		db.getTracksDao().insertTracksFromUnreadLogs()

		db.getTracksDao().getTracksCount() shouldBe 0
	}

	@Test
	fun repairWorkIdentitiesUsesProjectionBinding() = runTest {
		insertManga(100L)
		insertEntity(20L)
		insertBinding(mangaId = 100L, entityId = 20L)
		insertLog(mangaId = 100L, entityId = 10L, createdAt = 300L, unread = true)

		db.getTrackLogsDao().repairWorkIdentities()

		val log = db.getTrackLogsDao().dump().single()
		log.entityId shouldBe 20L
		log.ownerId shouldBe 20L
	}

	@Test
	fun deleteOrphansOnlyRemovesMissingProjections() = runTest {
		insertManga(100L)
		insertManga(200L)
		insertEntity(10L)
		insertLog(mangaId = 100L, entityId = 10L, createdAt = 300L, unread = true)
		insertLog(mangaId = 200L, entityId = null, createdAt = 301L, unread = true)
		insertLog(mangaId = 300L, entityId = null, createdAt = 302L, unread = true)
		insertLog(mangaId = 100L, entityId = 20L, createdAt = 303L, unread = true)

		db.getTrackLogsDao().deleteOrphans()

		db.getTrackLogsDao().count() shouldBe 3
	}

	@Test
	fun insertTracksFromUnreadLogsRestoresValidLog() = runTest {
		insertManga(100L)
		insertEntity(10L)
		insertLog(mangaId = 100L, entityId = 10L, createdAt = 300L, unread = true)
		enableForeignKeys()

		db.getTracksDao().insertTracksFromUnreadLogs()

		val track = db.getTracksDao().findByOwnerId(10L)
		track?.mangaId shouldBe 100L
		track?.entityId shouldBe 10L
		track?.newChapters shouldBe 1
	}

	private fun enableForeignKeys() {
		db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
	}

	private fun insertManga(mangaId: Long) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO manga(
				manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating,
				cover_url, large_cover_url, state, author, source, description, content_type
			) VALUES (?, 'Title', NULL, '', '', 0, 0, NULL, '', NULL, NULL, NULL, 'test', NULL, 'MANGA')
			""".trimIndent(),
			arrayOf(mangaId),
		)
	}

	private fun insertEntity(entityId: Long) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO entity(
				id, type, sync_id, primary_name, name_hash, aliases, created_at, last_accessed, access_count
			) VALUES (?, 'WORK', ?, 'Title', ?, NULL, 0, 0, 0)
			""".trimIndent(),
			arrayOf(entityId, "test-$entityId", entityId),
		)
	}

	private fun insertBinding(mangaId: Long, entityId: Long) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO entity_binding(
				entity_id, source, external_id, confidence, is_primary, source_kind,
				state, created_by, updated_at
			) VALUES (?, 'local_manga', ?, 1, 1, 'LOCAL', 'CONFIRMED', 'MIGRATION', 0)
			""".trimIndent(),
			arrayOf(entityId, mangaId.toString()),
		)
	}

	private fun insertTrack(
		ownerId: Long,
		mangaId: Long,
		entityId: Long?,
		newChapters: Int,
		checkedAt: Long,
	) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO tracks(
				owner_id, manga_id, entity_id, last_chapter_id, chapters_new,
				last_check_time, last_chapter_date, last_result, last_error
			) VALUES (?, ?, ?, 0, ?, ?, 0, 0, NULL)
			""".trimIndent(),
			arrayOf(ownerId, mangaId, entityId, newChapters, checkedAt),
		)
	}

	private fun insertLog(
		mangaId: Long,
		entityId: Long?,
		createdAt: Long,
		unread: Boolean,
	) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO track_logs(owner_id, manga_id, entity_id, chapters, created_at, unread)
			VALUES (?, ?, ?, 'Chapter', ?, ?)
			""".trimIndent(),
			arrayOf(entityId ?: -mangaId, mangaId, entityId, createdAt, if (unread) 1 else 0),
		)
	}
}

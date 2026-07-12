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

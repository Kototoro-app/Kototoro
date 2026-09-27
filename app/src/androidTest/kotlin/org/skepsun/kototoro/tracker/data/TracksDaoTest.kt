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
	fun unreadCountDeduplicatesMangaAcrossTracksAndLogs() = runTest {
		insertTrack(mangaId = 100L, newChapters = 2, checkedAt = 200L)
		insertLog(mangaId = 100L, createdAt = 210L, unread = true)
		insertLog(mangaId = 100L, createdAt = 220L, unread = true)
		insertLog(mangaId = 200L, createdAt = 230L, unread = true)

		db.getTracksDao().observeUnreadWorkCount().first() shouldBe 2
	}

	@Test
	fun unreadCountIncludesExistingUnreadRegardlessOfTimestamps() = runTest {
		insertTrack(mangaId = 100L, newChapters = 3, checkedAt = 300L)
		insertTrack(mangaId = 200L, newChapters = 1, checkedAt = 100L)
		insertLog(mangaId = 300L, createdAt = 300L, unread = false)
		insertLog(mangaId = 400L, createdAt = 300L, unread = true)

		db.getTracksDao().observeUnreadWorkCount().first() shouldBe 3
	}

	@Test
	fun insertTracksFromUnreadLogsSkipsOrphanContent() = runTest {
		insertLog(mangaId = 100L, createdAt = 300L, unread = true)
		enableForeignKeys()

		db.getTracksDao().insertTracksFromUnreadLogs()

		db.getTracksDao().getTracksCount() shouldBe 0
	}

	@Test
	fun deleteOrphansOnlyRemovesMissingContents() = runTest {
		insertManga(100L)
		insertManga(200L)
		insertLog(mangaId = 100L, createdAt = 300L, unread = true)
		insertLog(mangaId = 200L, createdAt = 301L, unread = true)
		insertLog(mangaId = 300L, createdAt = 302L, unread = true)
		insertLog(mangaId = 100L, createdAt = 303L, unread = true)

		db.getTrackLogsDao().deleteOrphans()

		db.getTrackLogsDao().count() shouldBe 3
	}

	@Test
	fun insertTracksFromUnreadLogsRestoresValidLog() = runTest {
		insertManga(100L)
		insertLog(mangaId = 100L, createdAt = 300L, unread = true)
		enableForeignKeys()

		db.getTracksDao().insertTracksFromUnreadLogs()

		val track = db.getTracksDao().find(100L)
		track?.mangaId shouldBe 100L
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

	private fun insertTrack(
		mangaId: Long,
		newChapters: Int,
		checkedAt: Long,
	) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO tracks(
				manga_id, last_chapter_id, chapters_new, last_check_time, last_chapter_date, last_result, last_error
			) VALUES (?, 0, ?, ?, 0, 0, NULL)
			""".trimIndent(),
			arrayOf<Any?>(mangaId, newChapters, checkedAt),
		)
	}

	private fun insertLog(
		mangaId: Long,
		createdAt: Long,
		unread: Boolean,
	) {
		db.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO track_logs(manga_id, chapters, created_at, unread)
			VALUES (?, 'Chapter', ?, ?)
			""".trimIndent(),
			arrayOf(mangaId, createdAt, if (unread) 1 else 0),
		)
	}
}

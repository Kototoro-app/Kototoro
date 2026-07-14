package org.skepsun.kototoro.space.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy

@RunWith(AndroidJUnit4::class)
class SpaceWorkDaoTest {

	private lateinit var db: MangaDatabase
	private val policy = DefaultSpaceContentPolicy()

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext(),
			MangaDatabase::class.java,
		).build()
		seedWorks()
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun mangaSpaceExcludesOtherMixedAndUnresolvedWorks() = runTest {
		val allowed = policy.allowedTypes(BuiltInSpaces.Manga).map { it.name }
		val classified = BuiltInSpaces.contexts.flatMap { context ->
			context.allowedContentTypes.map { it.name }
		}

		val histories = db.getWorkHistoryDao().findRecentForSpace(allowed, classified, 20)
		val favourites = db.getWorkFavouritesDao().findActiveForSpace(
			categoryId = null,
			allowedTypes = allowed,
			classifiedTypes = classified,
			oldestFirst = false,
			limit = 20,
		)

		assertEquals(listOf(1L), histories.map { it.entityId })
		assertEquals(listOf(1L), favourites.map { it.entityId })
	}

	private fun seedWorks() {
		val sql = db.openHelper.writableDatabase
		sql.execSQL(
			"INSERT INTO favourite_categories VALUES (1, 0, 0, 'Default', 'NEWEST', 0, 1, 0)",
		)
		(1L..5L).forEach { entityId ->
			sql.execSQL(
				"INSERT INTO entity VALUES (?, 'WORK', ?, ?, ?, NULL, 0, 0, 0)",
				arrayOf(entityId, "sync-$entityId", "Work $entityId", entityId),
			)
			sql.execSQL(
				"INSERT INTO work_history VALUES (?, ?, 0, ?, 0, 0, 0, 0, 0, 0, NULL)",
				arrayOf(entityId, entityId * 10, entityId),
			)
			sql.execSQL(
				"INSERT INTO work_favourites VALUES (?, 1, ?, 0, 0, 0, 0, ?)",
				arrayOf(entityId, entityId * 10, entityId),
			)
		}
		insertProjection(entityId = 1L, mangaId = 10L, contentType = ContentType.MANGA)
		insertProjection(entityId = 2L, mangaId = 20L, contentType = ContentType.VIDEO)
		insertProjection(entityId = 3L, mangaId = 30L, contentType = ContentType.MANGA)
		insertProjection(entityId = 3L, mangaId = 31L, contentType = ContentType.VIDEO)
		insertProjection(entityId = 4L, mangaId = 40L, contentType = null)
		insertProjection(entityId = 5L, mangaId = 50L, contentType = ContentType.OTHER)
	}

	private fun insertProjection(entityId: Long, mangaId: Long, contentType: ContentType?) {
		val sql = db.openHelper.writableDatabase
		sql.execSQL(
			"""
			INSERT INTO manga (
				manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating,
				cover_url, large_cover_url, state, author, source, description, content_type
			) VALUES (?, ?, NULL, '', '', 0, 0, NULL, '', NULL, NULL, NULL, 'TEST', NULL, ?)
			""".trimIndent(),
			arrayOf(mangaId, "Projection $mangaId", contentType?.name),
		)
		sql.execSQL(
			"""
			INSERT INTO entity_binding (
				entity_id, source, external_id, confidence, is_primary,
				source_kind, state, created_by, updated_at
			) VALUES (?, 'local_manga', ?, 1, 1, 'READING_SOURCE', 'CONFIRMED', 'LEGACY', 0)
			""".trimIndent(),
			arrayOf(entityId, mangaId.toString()),
		)
	}
}

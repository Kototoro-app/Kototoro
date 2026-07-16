package org.skepsun.kototoro.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaDatabaseTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		MangaDatabase::class.java,
	)

	private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

	@Test
	fun versions() {
		assertEquals(1, migrations.first().startVersion)
		repeat(migrations.size) { i ->
			assertEquals(i + 1, migrations[i].startVersion)
			assertEquals(i + 2, migrations[i].endVersion)
		}
		assertEquals(DATABASE_VERSION, migrations.last().endVersion)
	}

	@Test
	fun migrateAll() {
		helper.createDatabase(TEST_DB, 1).close()
		for (migration in migrations) {
			helper.runMigrationsAndValidate(
				TEST_DB,
				migration.endVersion,
				true,
				migration,
			).close()
		}
	}

	@Test
	fun prePopulate() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		helper.createDatabase(TEST_DB, DATABASE_VERSION).use {
			DatabasePrePopulateCallback(resources).onCreate(it)
		}
	}

	@Test
	fun migrate65To66CreatesWorkMigrationLedger() {
		helper.createDatabase(TEST_DB, 65).close()
		helper.runMigrationsAndValidate(
			TEST_DB,
			66,
			true,
			migrations.single { it.startVersion == 65 && it.endVersion == 66 },
		).use { db ->
			db.execSQL(
				"""
				INSERT INTO work_migration_ledger (
					legacy_table,
					legacy_key,
					legacy_checksum,
					target_entity_id,
					migration_version,
					status,
					migrated_at
				) VALUES ('favourites', 'manga=1;category=2', 'checksum-a', 10, 1, 'MIGRATED', 1000)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT OR REPLACE INTO work_migration_ledger (
					legacy_table,
					legacy_key,
					legacy_checksum,
					target_entity_id,
					migration_version,
					status,
					migrated_at
				) VALUES ('favourites', 'manga=1;category=2', 'checksum-b', 10, 1, 'NEEDS_REVIEW', 2000)
				""".trimIndent(),
			)
			db.query("SELECT COUNT(*), MAX(status) FROM work_migration_ledger").use { cursor ->
				cursor.moveToFirst()
				assertEquals(1, cursor.getInt(0))
				assertEquals("NEEDS_REVIEW", cursor.getString(1))
			}
		}
	}

	@Test
	fun migrate69To70AddsDescriptionColumn() {
		helper.createDatabase(TEST_DB, 69).use { db ->
			db.execSQL(
				"""
				INSERT INTO manga (
					manga_id,
					title,
					url,
					public_url,
					rating,
					nsfw,
					cover_url,
					source
				) VALUES (1, 'Test Title', 'http://example.com', '', 0.0, 0, '', 'Source')
				""".trimIndent()
			)
		}

		helper.runMigrationsAndValidate(
			TEST_DB,
			70,
			true,
			migrations.single { it.startVersion == 69 && it.endVersion == 70 },
		).use { db ->
			db.query("SELECT manga_id, description FROM manga WHERE manga_id = 1").use { cursor ->
				cursor.moveToFirst()
				assertEquals(1L, cursor.getLong(0))
				assertEquals(null, cursor.getString(1))
			}
		}
	}

	@Test
	fun migrate70To71AddsNullableContentTypeColumn() {
		helper.createDatabase(TEST_DB, 70).use { db ->
			db.execSQL(
				"""
				INSERT INTO manga (
					manga_id,
					title,
					url,
					public_url,
					rating,
					nsfw,
					cover_url,
					source
				) VALUES (1, 'Test Title', 'http://example.com', '', 0.0, 0, '', 'Source')
				""".trimIndent(),
			)
		}

		helper.runMigrationsAndValidate(
			TEST_DB,
			71,
			true,
			migrations.single { it.startVersion == 70 && it.endVersion == 71 },
		).use { db ->
			db.query("SELECT manga_id, content_type FROM manga WHERE manga_id = 1").use { cursor ->
				cursor.moveToFirst()
				assertEquals(1L, cursor.getLong(0))
				assertEquals(null, cursor.getString(1))
			}
		}
	}

	@Test
	fun migrate71To72CreatesSpaceSessionTables() {
		helper.createDatabase(TEST_DB, 71).close()

		helper.runMigrationsAndValidate(
			TEST_DB,
			72,
			true,
			migrations.single { it.startVersion == 71 && it.endVersion == 72 },
		).use { db ->
			db.execSQL(
				"""
				INSERT INTO space_session (
					space_id, selected_top_level, resume_kind, resume_entity_id,
					resume_projection_id, resume_route, route_schema_version,
					last_accessed, updated_at
				) VALUES ('builtin:manga', 'home', 'NONE', NULL, NULL, NULL, 1, 100, 100)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO space_navigation_entry (
					space_id, stack_key, position, route_kind, route_payload,
					route_schema_version, updated_at
				) VALUES ('builtin:manga', 'home', 0, 'TOP_LEVEL', '{}', 1, 100)
				""".trimIndent(),
			)
			db.query(
				"SELECT COUNT(*) FROM space_navigation_entry WHERE space_id = 'builtin:manga'",
			).use { cursor ->
				cursor.moveToFirst()
				assertEquals(1, cursor.getInt(0))
			}
		}
	}

	@Test
	fun migrate72To73CreatesSpaceRoutePreferences() {
		helper.createDatabase(TEST_DB, 72).close()

		helper.runMigrationsAndValidate(
			TEST_DB,
			73,
			true,
			migrations.single { it.startVersion == 72 && it.endVersion == 73 },
		).use { db ->
			db.execSQL(
				"""
				INSERT INTO space_route_preferences (
					space_id, route_key, payload, schema_version, updated_at
				) VALUES ('builtin:manga', 'main:list', '{}', 1, 100)
				""".trimIndent(),
			)
			db.query(
				"SELECT payload FROM space_route_preferences " +
					"WHERE space_id = 'builtin:manga' AND route_key = 'main:list'",
			).use { cursor ->
				cursor.moveToFirst()
				assertEquals("{}", cursor.getString(0))
			}
		}
	}

	@Test
	fun migrate74To75BackfillsUnambiguousWorkTypesAndLeavesMixedTypesUnknown() {
		helper.createDatabase(TEST_DB, 74).use { db ->
			db.execSQL(
				"""
				INSERT INTO manga (
					manga_id, title, url, public_url, rating, nsfw, cover_url, source, content_type
				) VALUES (1, '同名作品', 'manga://1', '', 0.0, 0, '', 'manga-source', 'MANGA')
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO manga (
					manga_id, title, url, public_url, rating, nsfw, cover_url, source, content_type
				) VALUES (2, '同名作品', 'video://2', '', 0.0, 0, '', 'video-source', 'VIDEO')
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO manga (
					manga_id, title, url, public_url, rating, nsfw, cover_url, source, content_type
				) VALUES (3, '单类型作品', 'manga://3', '', 0.0, 0, '', 'manga-source', 'MANGA')
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO manga (
					manga_id, title, url, public_url, rating, nsfw, cover_url, source, content_type
				) VALUES (4, '类型未知作品', 'unknown://4', '', 0.0, 0, '', 'unknown-source', NULL)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO entity (id, type, sync_id, primary_name, name_hash, aliases, created_at, last_accessed, access_count)
				VALUES (10, 'WORK', 'work-10', '同名作品', 10, NULL, 1, 1, 1)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO entity (id, type, sync_id, primary_name, name_hash, aliases, created_at, last_accessed, access_count)
				VALUES (20, 'WORK', 'work-20', '单类型作品', 20, NULL, 1, 1, 1)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO entity_binding (entity_id, source, external_id, confidence, is_primary)
				VALUES (10, 'local_manga', '1', 1.0, 1)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO entity_binding (entity_id, source, external_id, confidence, is_primary)
				VALUES (10, 'local_manga', '2', 1.0, 0)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO entity_binding (entity_id, source, external_id, confidence, is_primary)
				VALUES (20, 'local_manga', '3', 1.0, 1)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO entity_binding (entity_id, source, external_id, confidence, is_primary)
				VALUES (10, 'local_manga', '4', 1.0, 0)
				""".trimIndent(),
			)
		}

		helper.runMigrationsAndValidate(
			TEST_DB,
			75,
			true,
			migrations.single { it.startVersion == 74 && it.endVersion == 75 },
		).use { db ->
			db.query("SELECT id, content_type FROM entity ORDER BY id").use { cursor ->
				cursor.moveToFirst()
				assertEquals(10L, cursor.getLong(0))
				assertEquals(null, cursor.getString(1))
				cursor.moveToNext()
				assertEquals(20L, cursor.getLong(0))
				assertEquals("MANGA", cursor.getString(1))
			}
			db.query("PRAGMA index_info('idx_entity_name_hash')").use { cursor ->
				val columns = buildList {
					while (cursor.moveToNext()) add(cursor.getString(2))
				}
				assertEquals(listOf("type", "name_hash", "content_type"), columns)
			}
		}
	}

	private companion object {

		const val TEST_DB = "test-db"
	}
}

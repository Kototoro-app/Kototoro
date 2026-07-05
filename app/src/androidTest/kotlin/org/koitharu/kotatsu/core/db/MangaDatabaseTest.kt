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

	private companion object {

		const val TEST_DB = "test-db"
	}
}

package org.skepsun.kototoro.backups.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.backups.data.BackupRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupPayloadGuardTest {

	@Test
	fun `current Kototoro backups are detected by app id and semantic schema`() {
		for (appId in listOf("org.skepsun.kototoro", "org.skepsun.kototoro.nightly", "org.skepsun.kototoro.debug")) {
			for (schema in listOf(3, 4)) {
				assertEquals(
					BackupRestoreFormat.KOTOTORO_CURRENT,
					BackupPayloadGuard.detectRestoreFormat(indexedBackup(appId, semanticSchemaVersion = schema)),
				)
			}
		}
	}

	@Test
	fun `Kotatsu, legacy Kototoro and other apps are detected as the compat format`() {
		val compat = listOf(
			indexedBackup("io.github.kotatsuredo.kotatsu", semanticSchemaVersion = 1),
			indexedBackup(BuildConfig.APPLICATION_ID, semanticSchemaVersion = 2),
			indexedBackup("example.unrelated", semanticSchemaVersion = 3),
		)

		compat.forEach { backup ->
			assertEquals(BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO, BackupPayloadGuard.detectRestoreFormat(backup))
		}
		assertEquals(
			BackupRepository.RestoreMode.MERGE,
			BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO.defaultRestoreMode,
		)
		assertEquals(
			BackupRepository.RestoreMode.SNAPSHOT_REPLACE,
			BackupRestoreFormat.KOTOTORO_CURRENT.defaultRestoreMode,
		)
	}

	@Test
	fun `a file without a backup index is rejected`() {
		val notABackup = backupFile(BackupSection.HISTORY to "[]")

		assertThrows(BackupPayloadGuard.UnexpectedBackupFormatException::class.java) {
			BackupPayloadGuard.detectRestoreFormat(notABackup)
		}
	}

	@Test
	fun `completed work history with unknown chapter count remains restorable`() {
		val backup = backupFile(
			BackupSection.CATEGORIES to "[]",
			BackupSection.CONTENTS to """[{"id":42}]""",
			BackupSection.ENTITY_GRAPH_ENTITIES to """[{"id":1,"type":"WORK","sync_id":"work-1"}]""",
			BackupSection.ENTITY_GRAPH_BINDINGS to """[{"entity_id":1}]""",
			BackupSection.WORK_HISTORY to """
				[
					{
						"entity_id":1,
						"anchor_manga_id":42,
						"created_at":10,
						"updated_at":20,
						"chapter_id":100,
						"page":0,
						"scroll":0.0,
						"percent":1.0,
						"deleted_at":0,
						"chapters":0
					}
				]
			""".trimIndent(),
		)

		assertDoesNotThrow {
			BackupPayloadGuard.requireRestorableWorkSnapshot(backup, operation = "manual backup creation")
		}
	}

	@Test
	fun `work history still rejects missing content anchors`() {
		val backup = backupFile(
			BackupSection.CATEGORIES to "[]",
			BackupSection.CONTENTS to "[]",
			BackupSection.ENTITY_GRAPH_ENTITIES to """[{"id":1,"type":"WORK","sync_id":"work-1"}]""",
			BackupSection.ENTITY_GRAPH_BINDINGS to """[{"entity_id":1}]""",
			BackupSection.WORK_HISTORY to """
				[
					{
						"entity_id":1,
						"anchor_manga_id":42,
						"created_at":10,
						"updated_at":20,
						"chapter_id":100,
						"page":0,
						"scroll":0.0,
						"percent":1.0,
						"deleted_at":0,
						"chapters":0
					}
				]
			""".trimIndent(),
		)

		assertThrows(BackupPayloadGuard.MissingAnchorContentsException::class.java) {
			BackupPayloadGuard.requireRestorableWorkSnapshot(backup, operation = "manual backup creation")
		}
	}

	@Test
	fun `local backup guard errors do not mention WebDAV`() {
		val backup = backupFile(
			BackupSection.CATEGORIES to "[]",
			BackupSection.CONTENTS to """[{"id":42}]""",
			BackupSection.ENTITY_GRAPH_ENTITIES to """[{"id":1,"type":"WORK","sync_id":"work-1"}]""",
			BackupSection.ENTITY_GRAPH_BINDINGS to """[{"entity_id":1}]""",
			BackupSection.WORK_FAVOURITES to """[{"entity_id":1,"category_id":99,"anchor_manga_id":42,"deleted_at":0}]""",
		)

		val error = assertThrows(IllegalStateException::class.java) {
			BackupPayloadGuard.requireRestorableWorkSnapshot(backup, operation = "manual backup creation")
		}

		assertFalse(error.message.orEmpty().contains("WebDAV"))
	}

	@Test
	fun `legacy work state with a missing entity is still restorable onto its anchor`() {
		// Manga-keyed restore only needs anchor_manga_id; entity ids are payload.
		val backup = backupFile(
			BackupSection.CONTENTS to """[{"id":42,"title":"Readable title","source":"test-source"}]""",
			BackupSection.ENTITY_GRAPH_ENTITIES to "[]",
			BackupSection.ENTITY_GRAPH_BINDINGS to "[]",
			BackupSection.WORK_HISTORY to """[{"entity_id":99,"anchor_manga_id":42,"deleted_at":0}]""",
		)

		BackupPayloadGuard.requireRestorableWorkSnapshot(backup, operation = "manual backup creation")
	}

	private fun backupFile(vararg sections: Pair<BackupSection, String>): File {
		return File.createTempFile("backup_guard", ".zip").apply {
			deleteOnExit()
			ZipOutputStream(outputStream()).use { output ->
				sections.forEach { (section, json) ->
					output.putNextEntry(ZipEntry(section.entryName))
					output.write(json.toByteArray())
					output.closeEntry()
				}
			}
		}
	}

	private fun indexedBackup(appId: String, semanticSchemaVersion: Int): File = backupFile(
		BackupSection.INDEX to """
			[{
				"app_id":"$appId",
				"app_version":1,
				"semantic_schema_version":$semanticSchemaVersion,
				"created_at":1
			}]
		""".trimIndent(),
	)
}

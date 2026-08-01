package org.skepsun.kototoro.backups.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.BuildConfig
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupPayloadGuardTest {

	@Test
	fun `current Kototoro restore accepts only current semantic schema`() {
		val current = indexedBackup("org.skepsun.kototoro", semanticSchemaVersion = 3)
		val nightly = indexedBackup("org.skepsun.kototoro.nightly", semanticSchemaVersion = 3)
		val debug = indexedBackup("org.skepsun.kototoro.debug", semanticSchemaVersion = 3)
		val legacy = indexedBackup(BuildConfig.APPLICATION_ID, semanticSchemaVersion = 2)

		assertDoesNotThrow {
			BackupPayloadGuard.requireRestoreFormat(current, BackupRestoreFormat.KOTOTORO_CURRENT)
		}
		assertDoesNotThrow {
			BackupPayloadGuard.requireRestoreFormat(nightly, BackupRestoreFormat.KOTOTORO_CURRENT)
		}
		assertDoesNotThrow {
			BackupPayloadGuard.requireRestoreFormat(debug, BackupRestoreFormat.KOTOTORO_CURRENT)
		}
		assertThrows(BackupPayloadGuard.UnexpectedBackupFormatException::class.java) {
			BackupPayloadGuard.requireRestoreFormat(legacy, BackupRestoreFormat.KOTOTORO_CURRENT)
		}
	}

	@Test
	fun `compat restore accepts Kotatsu and legacy Kototoro but rejects current Kototoro`() {
		val kotatsu = indexedBackup("io.github.kotatsuredo.kotatsu", semanticSchemaVersion = 1)
		val legacyKototoro = indexedBackup(BuildConfig.APPLICATION_ID, semanticSchemaVersion = 1)
		val currentKototoro = indexedBackup(BuildConfig.APPLICATION_ID, semanticSchemaVersion = 3)
		val unrelatedCurrentFormat = indexedBackup("example.unrelated", semanticSchemaVersion = 3)

		assertDoesNotThrow {
			BackupPayloadGuard.requireRestoreFormat(kotatsu, BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO)
		}
		assertDoesNotThrow {
			BackupPayloadGuard.requireRestoreFormat(
				legacyKototoro,
				BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO,
			)
		}
		assertThrows(BackupPayloadGuard.UnexpectedBackupFormatException::class.java) {
			BackupPayloadGuard.requireRestoreFormat(
				currentKototoro,
				BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO,
			)
		}
		assertThrows(BackupPayloadGuard.UnexpectedBackupFormatException::class.java) {
			BackupPayloadGuard.requireRestoreFormat(
				unrelatedCurrentFormat,
				BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO,
			)
		}
	}

	@Test
	fun `completed work history with unknown chapter count remains restorable`() {
		val backup = backupFile(
			BackupSection.CATEGORIES to "[]",
			BackupSection.PROJECTIONS to """[{"id":42}]""",
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
	fun `work history still rejects missing projection anchors`() {
		val backup = backupFile(
			BackupSection.CATEGORIES to "[]",
			BackupSection.PROJECTIONS to "[]",
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

		assertThrows(BackupPayloadGuard.MissingProjectionAnchorsException::class.java) {
			BackupPayloadGuard.requireRestorableWorkSnapshot(backup, operation = "manual backup creation")
		}
	}

	@Test
	fun `local backup guard errors do not mention WebDAV`() {
		val backup = backupFile(
			BackupSection.CATEGORIES to "[]",
			BackupSection.PROJECTIONS to """[{"id":42}]""",
			BackupSection.ENTITY_GRAPH_ENTITIES to """[{"id":1,"type":"WORK","sync_id":"work-1"}]""",
			BackupSection.ENTITY_GRAPH_BINDINGS to """[{"entity_id":1}]""",
			BackupSection.WORK_FAVOURITES to """[{"entity_id":1,"category_id":99,"anchor_manga_id":42,"deleted_at":0}]""",
		)

		val error = assertThrows(IllegalStateException::class.java) {
			BackupPayloadGuard.requireRestorableWorkSnapshot(backup, operation = "manual backup creation")
		}

		assertFalse(error.message.orEmpty().contains("WebDAV"))
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

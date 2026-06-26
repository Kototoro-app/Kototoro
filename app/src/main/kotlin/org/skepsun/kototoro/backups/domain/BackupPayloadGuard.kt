package org.skepsun.kototoro.backups.domain

import java.io.File
import java.util.zip.ZipInputStream

object BackupPayloadGuard {

	data class Inspection(
		val sectionBytes: Map<BackupSection, Int>,
		val unknownEntries: Map<String, Int>,
	) {
		fun describe(): String {
			val known = BackupSection.entries
				.filter { it in sectionBytes }
				.joinToString { section -> "${section.name}:${sectionBytes.getValue(section)}b" }
			val unknown = unknownEntries.entries.joinToString { (name, bytes) -> "$name:${bytes}b" }
			return listOf(known, unknown)
				.filter { it.isNotBlank() }
				.joinToString()
		}
	}

	fun inspect(file: File): Inspection {
		val sectionBytes = LinkedHashMap<BackupSection, Int>()
		val unknownEntries = LinkedHashMap<String, Int>()
		ZipInputStream(file.inputStream()).use { input ->
			var entry = input.nextEntry
			while (entry != null) {
				val bytes = input.readBytes().size
				val section = BackupSection.of(entry)
				if (section != null) {
					sectionBytes[section] = bytes
				} else {
					unknownEntries[entry.name] = bytes
				}
				input.closeEntry()
				entry = input.nextEntry
			}
		}
		return Inspection(sectionBytes, unknownEntries)
	}

	fun requireRestorableWorkSnapshot(file: File, operation: String): Inspection {
		return inspect(file).also { inspection ->
			if (inspection.isIdentityOnlyWorkSnapshot()) {
				throw IllegalStateException(
					"Refusing $operation: WebDAV backup contains entity identity data but no work " +
						"favourites, history, or statistics. This incomplete snapshot would clear local user state.",
				)
			}
		}
	}

	private fun Inspection.isIdentityOnlyWorkSnapshot(): Boolean {
		val hasAuthoritativeIdentity = bytesOf(BackupSection.ENTITY_GRAPH_ENTITIES) > EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.ENTITY_GRAPH_BINDINGS) > EMPTY_JSON_ARRAY_BYTES
		if (!hasAuthoritativeIdentity) {
			return false
		}
		return bytesOf(BackupSection.WORK_HISTORY) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.WORK_FAVOURITES) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.WORK_STATS) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.HISTORY) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.FAVOURITES) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.STATS) <= EMPTY_JSON_ARRAY_BYTES
	}

	private fun Inspection.bytesOf(section: BackupSection): Int {
		return sectionBytes[section] ?: 0
	}

	private const val EMPTY_JSON_ARRAY_BYTES = 2
}

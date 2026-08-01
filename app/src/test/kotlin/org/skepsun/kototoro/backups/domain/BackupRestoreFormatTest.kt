package org.skepsun.kototoro.backups.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupRestoreFormatTest {

	@Test
	fun `legacy restore excludes sources and settings`() {
		val format = BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO

		assertFalse(format.supports(BackupSection.SOURCES))
		assertFalse(format.supports(BackupSection.SETTINGS))
		assertFalse(format.supports(BackupSection.SETTINGS_READER_GRID))
		assertFalse(format.supports(BackupSection.SAVED_FILTERS))
		assertFalse(format.supports(BackupSection.SCROBBLING))
		assertFalse(format.supports(BackupSection.AUTH))
		assertFalse(format.supports(BackupSection.EXTENSION_REPOS))
		assertTrue(format.supports(BackupSection.FAVOURITES))
		assertEquals(
			setOf(BackupSection.INDEX, BackupSection.FAVOURITES),
			format.sanitize(
				setOf(
					BackupSection.INDEX,
					BackupSection.SOURCES,
					BackupSection.SETTINGS,
					BackupSection.SETTINGS_READER_GRID,
					BackupSection.FAVOURITES,
				),
			),
		)
	}

	@Test
	fun `current Kototoro restore keeps all requested sections`() {
		val sections = BackupSection.entries.toSet()

		assertEquals(sections, BackupRestoreFormat.KOTOTORO_CURRENT.sanitize(sections))
	}
}

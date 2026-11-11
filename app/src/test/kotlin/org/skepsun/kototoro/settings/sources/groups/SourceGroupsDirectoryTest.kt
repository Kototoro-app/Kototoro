package org.skepsun.kototoro.settings.sources.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceGroupsDirectoryTest {

	@Test
	fun `resolve puts back the copy an interrupted swap left aside`(@TempDir filesDir: File) {
		val previous = SourceGroupsDirectory.previous(filesDir).apply { mkdirs() }
		File(previous, "a.json").writeText("{}")

		val dir = SourceGroupsDirectory.resolve(filesDir)

		assertEquals(listOf("a.json"), dir.list()?.toList())
		assertFalse(previous.exists())
	}

	@Test
	fun `resolve leaves an existing directory and its aside copy alone`(@TempDir filesDir: File) {
		val dir = File(filesDir, "source_groups").apply { mkdirs() }
		File(dir, "current.json").writeText("{}")
		val previous = SourceGroupsDirectory.previous(filesDir).apply { mkdirs() }

		assertEquals(dir, SourceGroupsDirectory.resolve(filesDir))

		assertEquals(listOf("current.json"), dir.list()?.toList())
		assertTrue(previous.exists())
	}
}

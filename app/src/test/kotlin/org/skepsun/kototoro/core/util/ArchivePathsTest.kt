package org.skepsun.kototoro.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArchivePathsTest {

	@TempDir
	lateinit var root: File

	@Test
	fun `entries inside the destination resolve under it`() {
		val dest = File(root, "model").apply { mkdirs() }

		assertEquals(File(dest, "det.nb").canonicalFile, archiveEntryFile(dest, "det.nb"))
		assertEquals(File(dest, "sub/rec.nb").canonicalFile, archiveEntryFile(dest, "sub\\rec.nb"))
		assertEquals(File(dest, "abs/cls.nb").canonicalFile, archiveEntryFile(dest, "/abs/cls.nb"))
		assertEquals(File(dest, "a.nb").canonicalFile, archiveEntryFile(dest, "sub/../a.nb"))
	}

	@Test
	fun `a sibling directory sharing the destination's name prefix is refused`() {
		val dest = File(root, "model").apply { mkdirs() }

		assertThrows(SecurityException::class.java) { archiveEntryFile(dest, "../model-evil/file") }
		assertThrows(SecurityException::class.java) { archiveEntryFile(dest, "..\\model-evil\\file") }
		assertThrows(SecurityException::class.java) { archiveEntryFile(dest, "../../outside") }
	}

	@Test
	fun `a symbolic link inside the destination does not lead out of it`() {
		val dest = File(root, "model").apply { mkdirs() }
		val outside = File(root, "outside").apply { mkdirs() }
		java.nio.file.Files.createSymbolicLink(File(dest, "link").toPath(), outside.toPath())

		assertThrows(SecurityException::class.java) { archiveEntryFile(dest, "link/file") }
	}
}

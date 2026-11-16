package org.skepsun.kototoro.core.util.zip

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset

class ZipEntryNamesTest {

	@TempDir
	lateinit var root: File

	@Test
	fun `a GBK archive reads its folder names back`() {
		val file = writeArchive("gbk.zip", "GBK", utf8Flag = false, entries = GBK_ENTRIES)

		val names = ZipEntryNames.read(file)

		assertFalse(names.isEmpty)
		assertEquals("01 春天", names.display(lossy("01 春天")))
		assertEquals("01 春天/001.jpg", names.display(lossy("01 春天/001.jpg")))
		assertEquals(lossy("01 春天"), names.archive("01 春天"))
	}

	@Test
	fun `every repaired name survives a round trip through the archive's own name`() {
		val names = ZipEntryNames.read(writeArchive("gbk.zip", "GBK", utf8Flag = false, entries = GBK_ENTRIES))

		for (entry in GBK_ENTRIES) {
			assertEquals(entry, names.display(names.archive(entry)))
		}
	}

	@Test
	fun `ascii names in a GBK archive are left alone`() {
		val names = ZipEntryNames.read(
			writeArchive("gbk.zip", "GBK", utf8Flag = false, entries = listOf("cover.jpg", "001.jpg")),
		)

		assertTrue(names.isEmpty)
		assertEquals("cover.jpg", names.display("cover.jpg"))
	}

	@Test
	fun `a UTF-8 archive needs no repair`() {
		val file = writeArchive("utf8.zip", "UTF-8", utf8Flag = true, entries = listOf("01 春天/001.jpg"))

		assertTrue(ZipEntryNames.read(file).isEmpty)
	}

	@Test
	fun `an un-flagged UTF-8 archive needs no repair either`() {
		val file = writeArchive("utf8-unflagged.zip", "UTF-8", utf8Flag = false, entries = listOf("01 春天/001.jpg"))

		assertTrue(ZipEntryNames.read(file).isEmpty)
	}

	@Test
	fun `a name that is neither UTF-8 nor CJK falls back to the spec's own charset`() {
		val file = writeArchive("cp437.zip", "IBM437", utf8Flag = false, entries = listOf("Café/001.jpg"))

		val names = ZipEntryNames.read(file)

		assertEquals("Café/001.jpg", names.display(lossy("Café/001.jpg", Charset.forName("IBM437"))))
	}

	@Test
	fun `a directory has no entry names to read`() {
		assertTrue(ZipEntryNames.read(root).isEmpty)
	}

	@Test
	fun `a file that is not an archive has no entry names to read`() {
		val file = File(root, "notes.txt").apply { writeText("not a zip") }

		assertTrue(ZipEntryNames.read(file).isEmpty)
	}

	private fun writeArchive(
		name: String,
		charset: String,
		utf8Flag: Boolean,
		entries: List<String>,
	): File {
		val file = File(root, name)
		ZipArchiveOutputStream(file).use { zip ->
			zip.setEncoding(charset)
			zip.setUseLanguageEncodingFlag(utf8Flag)
			for (entry in entries) {
				zip.putArchiveEntry(ZipArchiveEntry(entry))
				zip.write(0x42)
				zip.closeArchiveEntry()
			}
		}
		return file
	}

	/** What `ZipFile` and okio show for a name written in [charset] without the UTF-8 flag. */
	private fun lossy(name: String, charset: Charset = Charset.forName("GBK")): String =
		String(name.toByteArray(charset), Charsets.UTF_8)

	private companion object {
		val GBK_ENTRIES = listOf(
			"01 春天/",
			"01 春天/001.jpg",
			"02 夏天/001.jpg",
			"03 秋天/001.jpg",
		)
	}
}

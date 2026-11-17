package org.skepsun.kototoro.core.util.zip

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.CRC32

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
	fun `a mixed archive repairs the un-flagged names only`() {
		val file = writeRawArchive(
			"mixed.zip",
			listOf(
				RawEntry("01 春天/001.jpg".toByteArray(Charsets.UTF_8), utf8Flagged = true),
				RawEntry("02 夏天/001.jpg".toByteArray(Charset.forName("GBK")), utf8Flagged = false),
			),
		)

		val names = ZipEntryNames.read(file)

		assertFalse(names.isEmpty)
		assertEquals("02 夏天/001.jpg", names.display(lossy("02 夏天/001.jpg")))
		assertEquals("01 春天/001.jpg", names.display("01 春天/001.jpg"))
		assertEquals(lossy("02 夏天/001.jpg"), names.archive("02 夏天/001.jpg"))
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

	/** One stored entry of a hand-built archive: the flag is per entry, so it cannot come from a stream setting. */
	private class RawEntry(val nameBytes: ByteArray, val utf8Flagged: Boolean)

	/**
	 * Writes a stored, uncompressed archive entry by entry, because `ZipArchiveOutputStream` picks one
	 * encoding for the whole stream and can therefore never produce a mixed-flag archive.
	 */
	private fun writeRawArchive(name: String, entries: List<RawEntry>): File {
		val data = byteArrayOf(0x42)
		val crc = CRC32().apply { update(data) }.value.toInt()
		val body = ByteArrayOutputStream()
		val central = ByteArrayOutputStream()
		for (entry in entries) {
			val flags = if (entry.utf8Flagged) UTF8_FLAG else 0
			val offset = body.size()
			body.write(
				ByteBuffer.allocate(30 + entry.nameBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
					putInt(LOCAL_FILE_HEADER)
					putShort(VERSION.toShort())
					putShort(flags.toShort())
					putShort(0) // method: stored
					putShort(0) // time
					putShort(0) // date
					putInt(crc)
					putInt(data.size)
					putInt(data.size)
					putShort(entry.nameBytes.size.toShort())
					putShort(0) // extra field length
					put(entry.nameBytes)
				}.array(),
			)
			body.write(data)
			central.write(
				ByteBuffer.allocate(46 + entry.nameBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
					putInt(CENTRAL_FILE_HEADER)
					putShort(VERSION.toShort())
					putShort(VERSION.toShort())
					putShort(flags.toShort())
					putShort(0) // method: stored
					putShort(0) // time
					putShort(0) // date
					putInt(crc)
					putInt(data.size)
					putInt(data.size)
					putShort(entry.nameBytes.size.toShort())
					putShort(0) // extra field length
					putShort(0) // comment length
					putShort(0) // disk number start
					putShort(0) // internal attributes
					putInt(0) // external attributes
					putInt(offset)
					put(entry.nameBytes)
				}.array(),
			)
		}
		val directory = central.toByteArray()
		val directoryOffset = body.size()
		body.write(directory)
		body.write(
			ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
				putInt(END_OF_CENTRAL_DIRECTORY)
				putShort(0) // this disk
				putShort(0) // disk with the central directory
				putShort(entries.size.toShort())
				putShort(entries.size.toShort())
				putInt(directory.size)
				putInt(directoryOffset)
				putShort(0) // comment length
			}.array(),
		)
		return File(root, name).apply { writeBytes(body.toByteArray()) }
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
		const val LOCAL_FILE_HEADER = 0x04034b50
		const val CENTRAL_FILE_HEADER = 0x02014b50
		const val END_OF_CENTRAL_DIRECTORY = 0x06054b50
		const val VERSION = 20
		const val UTF8_FLAG = 0x800

		val GBK_ENTRIES = listOf(
			"01 春天/",
			"01 春天/001.jpg",
			"02 夏天/001.jpg",
			"03 秋天/001.jpg",
		)
	}
}

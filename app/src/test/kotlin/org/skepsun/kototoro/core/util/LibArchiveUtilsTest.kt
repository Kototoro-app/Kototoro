package org.skepsun.kototoro.core.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The archive plumbing itself lives in libarchive (native), so these tests drive the same
 * entry-buffer/limit logic that [LibArchiveUtils.getByteArrayContent] uses, feeding it from a
 * real zip stream produced with [ZipOutputStream].
 */
class LibArchiveUtilsTest {

	@Test
	fun `entry content below the limit is returned as is`() {
		val content = "chapter 1".toByteArray()

		val read = readEntry(zipOf(ENTRY_NAME, content), ENTRY_NAME, LibArchiveUtils.DEFAULT_MAX_ENTRY_BYTES)

		assertArrayEquals(content, read)
	}

	@Test
	fun `entry content exactly at the limit is accepted`() {
		val content = ByteArray(8) { it.toByte() }

		val read = readEntry(zipOf(ENTRY_NAME, content), ENTRY_NAME, content.size.toLong())

		assertArrayEquals(content, read)
	}

	@Test
	fun `entry content above the limit fails fast`() {
		val zip = zipOf(ENTRY_NAME, ByteArray(16) { it.toByte() })

		val exception = assertThrows(IOException::class.java) {
			readEntry(zip, ENTRY_NAME, 8L)
		}

		assertMessageMentions(exception, ENTRY_NAME, "8")
	}

	@Test
	fun `declared size above the limit is rejected before reading`() {
		val exception = assertThrows(IOException::class.java) {
			LibArchiveUtils.checkEntrySizeLimit(ENTRY_NAME, 1024L, 64L)
		}

		assertMessageMentions(exception, ENTRY_NAME, "64")
	}

	@Test
	fun `declared size at or below the limit passes`() {
		assertDoesNotThrow {
			LibArchiveUtils.checkEntrySizeLimit(ENTRY_NAME, 64L, 64L)
			LibArchiveUtils.checkEntrySizeLimit(ENTRY_NAME, 0L, 64L)
		}
	}

	private fun assertMessageMentions(exception: Throwable, vararg fragments: String) {
		val message = exception.message.orEmpty()
		fragments.forEach { fragment ->
			assertEquals(true, message.contains(fragment), "expected \"$fragment\" in \"$message\"")
		}
	}

	private fun zipOf(name: String, content: ByteArray): ByteArray {
		val output = ByteArrayOutputStream()
		ZipOutputStream(output).use { zip ->
			zip.putNextEntry(ZipEntry(name))
			zip.write(content)
			zip.closeEntry()
		}
		return output.toByteArray()
	}

	private fun readEntry(zip: ByteArray, name: String, maxBytes: Long): ByteArray {
		val content = LibArchiveUtils.EntryBuffer(name, maxBytes)
		ZipInputStream(ByteArrayInputStream(zip)).use { input ->
			val entry = input.nextEntry ?: error("no entry in test archive")
			assertEquals(name, entry.name)
			val chunk = ByteBuffer.allocate(CHUNK_SIZE)
			while (true) {
				chunk.clear()
				val read = input.read(chunk.array())
				if (read <= 0) break
				chunk.limit(read)
				content.write(chunk)
			}
		}
		return content.toByteArray()
	}

	private companion object {
		const val ENTRY_NAME = "OEBPS/chapter-1.xhtml"
		const val CHUNK_SIZE = 4
	}
}

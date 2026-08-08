package org.skepsun.kototoro.core.parser.tvbox

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

class TVBoxProtectedInitDetectorTest {

	@Test
	fun `Init with process termination and no host allowance is protected`() {
		val dex = createDex(initString = "com.github.tvbox.osc", includeKillProcess = true)

		assertTrue(TVBoxProtectedInitDetector.isProtectedDex(dex, "org.skepsun.kototoro"))
	}

	@Test
	fun `allowed host uses standard initialization even when Init contains process termination`() {
		val dex = createDex(initString = "com.github.tvbox.osc, org.skepsun.kototoro", includeKillProcess = true)

		assertFalse(TVBoxProtectedInitDetector.isProtectedDex(dex, "org.skepsun.kototoro"))
	}

	@Test
	fun `System exit is treated as protected termination`() {
		val dex = createDex(initString = "com.github.tvbox.osc", includeKillProcess = false, includeSystemExit = true)

		assertTrue(TVBoxProtectedInitDetector.isProtectedDex(dex, "org.skepsun.kototoro"))
	}

	@Test
	fun `encrypted host allowance uses standard initialization`() {
		val dex = createDex(initString = encrypt("org.skepsun.kototoro"), includeKillProcess = true)

		assertFalse(TVBoxProtectedInitDetector.isProtectedDex(dex, "org.skepsun.kototoro"))
	}

	@Test
	fun `native Guard Init without process termination uses standard initialization`() {
		val dex = createDex(initString = "unrelated", includeKillProcess = false)

		assertFalse(TVBoxProtectedInitDetector.isProtectedDex(dex, "org.skepsun.kototoro"))
	}

	@Test
	fun `detector scans dex entries inside jar`(@TempDir tempDir: Path) {
		val jar = tempDir.resolve("spider.jar")
		ZipOutputStream(jar.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("classes.dex"))
			zip.write(createDex(initString = "other.host", includeKillProcess = true))
			zip.closeEntry()
		}

		assertTrue(TVBoxProtectedInitDetector.isProtected(jar.toFile(), "org.skepsun.kototoro"))
	}

	@Test
	fun `malformed dex fails closed to standard initialization`(@TempDir tempDir: Path) {
		val dex = tempDir.resolve("broken.dex")
		dex.writeBytes(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte()))

		assertFalse(TVBoxProtectedInitDetector.isProtected(dex.toFile(), "org.skepsun.kototoro"))
	}

	private fun createDex(
		initString: String,
		includeKillProcess: Boolean,
		includeSystemExit: Boolean = false,
	): ByteArray {
		val strings = listOf(
			"Lcom/github/catvod/spider/Init;",
			"Landroid/os/Process;",
			"Ljava/lang/System;",
			"init",
			"killProcess",
			"exit",
			initString,
		)
		val data = ByteArray(1024)
		"dex\n035\u0000".toByteArray().copyInto(data)

		val stringIdsOffset = 0x70
		val typeIdsOffset = stringIdsOffset + strings.size * 4
		val methodIdsOffset = typeIdsOffset + 3 * 4
		val methodCount = 2 + if (includeSystemExit) 1 else 0
		val classDefsOffset = methodIdsOffset + methodCount * 8
		val classDataOffset = 0x100
		val initCodeOffset = 0x120
		val killCodeOffset = 0x140
		var stringDataOffset = 0x180

		data.putInt(0x38, strings.size)
		data.putInt(0x3c, stringIdsOffset)
		data.putInt(0x40, 3)
		data.putInt(0x44, typeIdsOffset)
		data.putInt(0x58, methodCount)
		data.putInt(0x5c, methodIdsOffset)
		data.putInt(0x60, 1)
		data.putInt(0x64, classDefsOffset)

		strings.forEachIndexed { index, value ->
			data.putInt(stringIdsOffset + index * 4, stringDataOffset)
			stringDataOffset += data.putStringData(stringDataOffset, value)
		}
		data.putInt(typeIdsOffset, 0)
		data.putInt(typeIdsOffset + 4, 1)
		data.putInt(typeIdsOffset + 8, 2)

		data.putShort(methodIdsOffset, 0)
		data.putInt(methodIdsOffset + 4, 3)
		data.putShort(methodIdsOffset + 8, 1)
		data.putInt(methodIdsOffset + 12, 4)
		if (includeSystemExit) {
			data.putShort(methodIdsOffset + 16, 2)
			data.putInt(methodIdsOffset + 20, 5)
		}

		data.putInt(classDefsOffset, 0)
		data.putInt(classDefsOffset + 24, classDataOffset)

		val classData = ByteArrayOutputStream().apply {
			writeUleb128(0)
			writeUleb128(0)
			writeUleb128(1 + (if (includeKillProcess) 1 else 0) + (if (includeSystemExit) 1 else 0))
			writeUleb128(0)
			writeUleb128(0)
			writeUleb128(0)
			writeUleb128(initCodeOffset)
			if (includeKillProcess) {
				writeUleb128(1)
				writeUleb128(0)
				writeUleb128(killCodeOffset)
			}
			if (includeSystemExit) {
				writeUleb128(if (includeKillProcess) 1 else 1)
				writeUleb128(0)
				writeUleb128(if (includeKillProcess) 0x160 else 0x140)
			}
		}.toByteArray()
		classData.copyInto(data, classDataOffset)

		data.putInt(initCodeOffset + 12, 2)
		data[initCodeOffset + 16] = 0x1a
		data.putShort(initCodeOffset + 18, 6)
		if (includeKillProcess) {
			data.putInt(killCodeOffset + 12, 3)
			data[killCodeOffset + 16] = 0x71
			data.putShort(killCodeOffset + 18, 1)
		}
		if (includeSystemExit) {
			val offset = if (includeKillProcess) 0x160 else 0x140
			data.putInt(offset + 12, 3)
			data[offset + 16] = 0x71
			data.putShort(offset + 18, 2)
		}
		return data
	}

	private fun encrypt(value: String): String {
		val key = "1234123412341234".toByteArray()
		val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
		cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
		return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
	}

	private fun ByteArray.putStringData(offset: Int, value: String): Int {
		val output = ByteArrayOutputStream()
		output.writeUleb128(value.length)
		output.write(value.toByteArray())
		output.write(0)
		return output.toByteArray().also { bytes -> bytes.copyInto(this, offset) }.size
	}

	private fun ByteArray.putShort(offset: Int, value: Int) {
		this[offset] = value.toByte()
		this[offset + 1] = (value ushr 8).toByte()
	}

	private fun ByteArray.putInt(offset: Int, value: Int) {
		putShort(offset, value)
		putShort(offset + 2, value ushr 16)
	}

	private fun ByteArrayOutputStream.writeUleb128(value: Int) {
		var remaining = value
		do {
			var byte = remaining and 0x7f
			remaining = remaining ushr 7
			if (remaining != 0) byte = byte or 0x80
			write(byte)
		} while (remaining != 0)
	}
}

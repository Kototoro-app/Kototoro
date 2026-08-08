package org.skepsun.kototoro.core.parser.tvbox

import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object TVBoxProtectedInitDetector {

	private const val INIT_DESCRIPTOR = "Lcom/github/catvod/spider/Init;"
	private const val INIT_DESCRIPTOR_PREFIX = "Lcom/github/catvod/spider/Init"
	private const val PROCESS_DESCRIPTOR = "Landroid/os/Process;"
	private const val SYSTEM_DESCRIPTOR = "Ljava/lang/System;"
	private const val AES_KEY = "1234123412341234"
	private val dexMagic = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte())

	fun isProtected(file: File, packageName: String): Boolean = runCatching {
		when {
			file.inputStream().use { input ->
				val magic = ByteArray(dexMagic.size)
				input.read(magic) == magic.size && magic.contentEquals(dexMagic)
			} -> isProtectedDex(file.readBytes(), packageName)
			else -> ZipFile(file).use { zip ->
				zip.entries().asSequence()
					.filterNot { it.isDirectory }
					.filter { it.name.endsWith(".dex", ignoreCase = true) }
					.any { entry -> zip.getInputStream(entry).use { isProtectedDex(it.readBytes(), packageName) } }
			}
		}
	}.getOrDefault(false)

	internal fun isProtectedDex(data: ByteArray, packageName: String): Boolean {
		if (data.size < DEX_HEADER_SIZE || !data.startsWith(dexMagic)) return false
		return runCatching { DexReader(data, packageName).isProtected() }.getOrDefault(false)
	}

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
		return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
	}

	private class DexReader(
		private val data: ByteArray,
		packageName: String,
	) {
		private val strings = Table(size = uint(0x38), offset = uint(0x3c), itemSize = 4)
		private val types = Table(size = uint(0x40), offset = uint(0x44), itemSize = 4)
		private val methods = Table(size = uint(0x58), offset = uint(0x5c), itemSize = 8)
		private val classes = Table(size = uint(0x60), offset = uint(0x64), itemSize = 32)
		private val whitelist = PackageWhitelist(packageName)

		fun isProtected(): Boolean {
			validateTable(strings)
			validateTable(types)
			validateTable(methods)
			validateTable(classes)

			var hasInit = false
			var hasTermination = false
			var currentPackageAllowed = false
			for (index in 0 until classes.size) {
				val classOffset = classes.itemOffset(index)
				val descriptor = typeDescriptor(uint(classOffset))
				if (!descriptor.startsWith(INIT_DESCRIPTOR_PREFIX)) continue

				val isInitClass = descriptor == INIT_DESCRIPTOR
				hasInit = hasInit || isInitClass
				val classDataOffset = uint(classOffset + 24)
				if (classDataOffset == 0) continue
				val methodsInClass = encodedMethods(classDataOffset)
				if (isInitClass) {
					currentPackageAllowed = methodsInClass
						.filter { methodName(it.methodIndex) == "init" }
						.any { method -> method.codeOffset != 0 && codeContainsPackage(method.codeOffset) }
				}
				hasTermination = hasTermination || methodsInClass.any { method ->
					method.codeOffset != 0 && codeInvokesTermination(method.codeOffset)
				}
			}
			return hasInit && hasTermination && !currentPackageAllowed
		}

		private fun encodedMethods(classDataOffset: Int): List<EncodedMethod> {
			val cursor = Cursor(classDataOffset)
			val staticFieldCount = uleb128(cursor)
			val instanceFieldCount = uleb128(cursor)
			val directMethodCount = uleb128(cursor)
			val virtualMethodCount = uleb128(cursor)
			repeat(staticFieldCount + instanceFieldCount) {
				uleb128(cursor)
				uleb128(cursor)
			}
			return buildList(directMethodCount + virtualMethodCount) {
				readEncodedMethods(cursor, directMethodCount, this)
				readEncodedMethods(cursor, virtualMethodCount, this)
			}
		}

		private fun readEncodedMethods(cursor: Cursor, count: Int, destination: MutableList<EncodedMethod>) {
			var methodIndex = 0
			repeat(count) {
				methodIndex += uleb128(cursor)
				uleb128(cursor)
				val codeOffset = uleb128(cursor)
				if (methodIndex in 0 until methods.size) {
					destination += EncodedMethod(methodIndex, codeOffset)
				}
			}
		}

		private fun codeContainsPackage(codeOffset: Int): Boolean {
			return scanCode(codeOffset) { instructionOffset, opcode ->
				when (opcode) {
					0x1a -> whitelist.contains(string(ushort(instructionOffset + 2)))
					0x1b -> whitelist.contains(string(uint(instructionOffset + 2)))
					else -> false
				}
			}
		}

		private fun codeInvokesTermination(codeOffset: Int): Boolean {
			return scanCode(codeOffset) { instructionOffset, opcode ->
				if (opcode !in 0x6e..0x72 && opcode !in 0x74..0x78) return@scanCode false
				val methodIndex = ushort(instructionOffset + 2)
				val owner = methodClass(methodIndex)
				val name = methodName(methodIndex)
				(owner == PROCESS_DESCRIPTOR && name == "killProcess") ||
					(owner == SYSTEM_DESCRIPTOR && name == "exit")
			}
		}

		private inline fun scanCode(codeOffset: Int, predicate: (offset: Int, opcode: Int) -> Boolean): Boolean {
			val instructionCount = uint(codeOffset + 12)
			val start = checkedAdd(codeOffset, 16)
			val end = checkedAdd(start, checkedMultiply(instructionCount, 2))
			require(end <= data.size)
			var offset = start
			while (offset + 1 < end) {
				if (predicate(offset, data[offset].toInt() and 0xff)) return true
				offset += 2
			}
			return false
		}

		private fun methodClass(methodIndex: Int): String {
			if (methodIndex !in 0 until methods.size) return ""
			return typeDescriptor(ushort(methods.itemOffset(methodIndex)))
		}

		private fun methodName(methodIndex: Int): String {
			if (methodIndex !in 0 until methods.size) return ""
			return string(uint(methods.itemOffset(methodIndex) + 4))
		}

		private fun typeDescriptor(typeIndex: Int): String {
			if (typeIndex !in 0 until types.size) return ""
			return string(uint(types.itemOffset(typeIndex)))
		}

		private fun string(stringIndex: Int): String {
			if (stringIndex !in 0 until strings.size) return ""
			val cursor = Cursor(uint(strings.itemOffset(stringIndex)))
			uleb128(cursor)
			val start = cursor.offset
			var end = start
			while (end < data.size && data[end].toInt() != 0) end++
			require(end < data.size)
			return data.decodeToString(start, end)
		}

		private fun validateTable(table: Table) {
			require(table.size >= 0 && table.offset >= 0)
			require(checkedAdd(table.offset, checkedMultiply(table.size, table.itemSize)) <= data.size)
		}

		private fun uleb128(cursor: Cursor): Int {
			var result = 0
			var shift = 0
			repeat(5) {
				require(cursor.offset < data.size)
				val value = data[cursor.offset++].toInt() and 0xff
				result = result or ((value and 0x7f) shl shift)
				if (value and 0x80 == 0) return result
				shift += 7
			}
			throw IllegalArgumentException("Invalid DEX uleb128 value")
		}

		private fun ushort(offset: Int): Int {
			require(offset >= 0 && offset + 2 <= data.size)
			return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
		}

		private fun uint(offset: Int): Int {
			require(offset >= 0 && offset + 4 <= data.size)
			return (data[offset].toInt() and 0xff) or
				((data[offset + 1].toInt() and 0xff) shl 8) or
				((data[offset + 2].toInt() and 0xff) shl 16) or
				((data[offset + 3].toInt() and 0xff) shl 24)
		}

		private fun checkedAdd(left: Int, right: Int): Int = Math.addExact(left, right)

		private fun checkedMultiply(left: Int, right: Int): Int = Math.multiplyExact(left, right)

		private data class Table(val size: Int, val offset: Int, val itemSize: Int) {
			fun itemOffset(index: Int): Int = Math.addExact(offset, Math.multiplyExact(index, itemSize))
		}

		private data class EncodedMethod(val methodIndex: Int, val codeOffset: Int)

		private data class Cursor(var offset: Int)
	}

	private class PackageWhitelist(private val packageName: String) {

		fun contains(value: String): Boolean {
			if (packageName.isBlank() || value.isBlank()) return false
			return containsPackage(value) || decrypt(value)?.let(::containsPackage) == true
		}

		private fun containsPackage(value: String): Boolean {
			return value.split(',').any { candidate -> candidate.trim() == packageName }
		}

		private fun decrypt(value: String): String? = runCatching {
			if (value.length < 24 || value.length % 4 != 0) return null
			val key = AES_KEY.toByteArray(Charsets.UTF_8)
			val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
			cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
			cipher.doFinal(Base64.getMimeDecoder().decode(value)).toString(Charsets.UTF_8)
		}.getOrNull()
	}

	private const val DEX_HEADER_SIZE = 112
}

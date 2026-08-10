package org.skepsun.kototoro.cloudstream.runtime

import java.io.File
import java.util.zip.ZipFile

internal object CloudstreamPluginCompatibilityChecker {

	fun inspect(
		pluginFile: File,
		hostClassLoader: ClassLoader,
	): CloudstreamPluginCompatibility {
		return runCatching {
			val referencedHostClasses = ZipFile(pluginFile).use { zip ->
				val dexEntries = zip.entries().asSequence()
					.filterNot { it.isDirectory }
					.filter { it.name.endsWith(".dex", ignoreCase = true) }
					.toList()
				require(dexEntries.isNotEmpty()) { "Plugin archive does not contain DEX bytecode" }
				dexEntries
					.asSequence()
					.flatMap { entry ->
						zip.getInputStream(entry).use { input ->
							DexTypeDescriptorReader.read(input.readBytes()).asSequence()
						}
					}
					.mapNotNull(::cloudstreamClassName)
					.toSortedSet()
			}
			val missingClasses = referencedHostClasses.filterNot { className ->
				runCatching { hostClassLoader.loadClass(className) }.isSuccess
			}
			if (missingClasses.isEmpty()) {
				CloudstreamPluginCompatibility.Compatible
			} else {
				CloudstreamPluginCompatibility.Incompatible(
					reason = "Requires unsupported Cloudstream host classes: ${missingClasses.joinToString()}",
					missingHostClasses = missingClasses,
				)
			}
		}.getOrElse { error ->
			CloudstreamPluginCompatibility.Incompatible(
				reason = "Invalid Cloudstream plugin archive: ${error.message ?: error.javaClass.simpleName}",
			)
		}
	}

	private fun cloudstreamClassName(descriptor: String): String? {
		val classDescriptor = descriptor.dropWhile { it == '[' }
		if (!classDescriptor.startsWith(CLOUDSTREAM_DESCRIPTOR_PREFIX) || !classDescriptor.endsWith(';')) {
			return null
		}
		return classDescriptor
			.removePrefix("L")
			.removeSuffix(";")
			.replace('/', '.')
	}

	private const val CLOUDSTREAM_DESCRIPTOR_PREFIX = "Lcom/lagradost/cloudstream3/"
}

internal sealed interface CloudstreamPluginCompatibility {
	data object Compatible : CloudstreamPluginCompatibility

	data class Incompatible(
		val reason: String,
		val missingHostClasses: List<String> = emptyList(),
	) : CloudstreamPluginCompatibility
}

private object DexTypeDescriptorReader {

	fun read(data: ByteArray): List<String> {
		require(data.size >= DEX_HEADER_SIZE && data.startsWith(DEX_MAGIC)) { "Invalid DEX header" }
		val strings = Table(size = data.uint(STRING_IDS_SIZE_OFFSET), offset = data.uint(STRING_IDS_OFFSET), itemSize = 4)
		val types = Table(size = data.uint(TYPE_IDS_SIZE_OFFSET), offset = data.uint(TYPE_IDS_OFFSET), itemSize = 4)
		data.validate(strings)
		data.validate(types)
		return buildList(types.size) {
			for (index in 0 until types.size) {
				val descriptorIndex = data.uint(types.itemOffset(index))
				add(data.string(strings, descriptorIndex))
			}
		}
	}

	private fun ByteArray.string(strings: Table, index: Int): String {
		require(index in 0 until strings.size) { "Invalid DEX string index" }
		val cursor = Cursor(uint(strings.itemOffset(index)))
		uleb128(cursor)
		val start = cursor.offset
		var end = start
		while (end < size && this[end].toInt() != 0) end++
		require(end < size) { "Unterminated DEX string" }
		return decodeToString(start, end)
	}

	private fun ByteArray.uleb128(cursor: Cursor): Int {
		var result = 0
		var shift = 0
		repeat(5) {
			require(cursor.offset < size) { "Invalid DEX uleb128 value" }
			val value = this[cursor.offset++].toInt() and 0xff
			result = result or ((value and 0x7f) shl shift)
			if (value and 0x80 == 0) return result
			shift += 7
		}
		throw IllegalArgumentException("Invalid DEX uleb128 value")
	}

	private fun ByteArray.validate(table: Table) {
		require(table.size >= 0 && table.offset >= 0) { "Invalid DEX table" }
		val tableEnd = Math.addExact(table.offset, Math.multiplyExact(table.size, table.itemSize))
		require(tableEnd <= size) { "DEX table exceeds file bounds" }
	}

	private fun ByteArray.uint(offset: Int): Int {
		require(offset >= 0 && offset + 4 <= size) { "Invalid DEX offset" }
		return (this[offset].toInt() and 0xff) or
			((this[offset + 1].toInt() and 0xff) shl 8) or
			((this[offset + 2].toInt() and 0xff) shl 16) or
			((this[offset + 3].toInt() and 0xff) shl 24)
	}

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
		return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
	}

	private data class Table(
		val size: Int,
		val offset: Int,
		val itemSize: Int,
	) {
		fun itemOffset(index: Int): Int = Math.addExact(offset, Math.multiplyExact(index, itemSize))
	}

	private data class Cursor(var offset: Int)

	private const val DEX_HEADER_SIZE = 112
	private const val STRING_IDS_SIZE_OFFSET = 0x38
	private const val STRING_IDS_OFFSET = 0x3c
	private const val TYPE_IDS_SIZE_OFFSET = 0x40
	private const val TYPE_IDS_OFFSET = 0x44
	private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte())
}

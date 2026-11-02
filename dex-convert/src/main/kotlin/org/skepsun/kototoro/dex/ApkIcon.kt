package org.skepsun.kototoro.dex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Resolves the manifest's launcher resource, including obfuscated paths, without Android framework classes. */
object ApkIcon {
    const val ENTRY = "META-INF/kototoro/extension-icon"
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun read(zip: ZipFile, icon: String? = null): ByteArray? {
        return try {
            // Repository JARs can carry the APK resources with a text manifest instead of our normalized entry.
            val reference = icon ?: bytes(zip, "AndroidManifest.xml")?.toString(Charsets.UTF_8)?.let {
                Regex("""<application\b[^>]*\bandroid:icon\s*=\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            }
            val paths = if (reference?.startsWith("@") == true) {
                val table = bytes(zip, "resources.arsc") ?: return null
                Table(table).paths(reference)
            } else listOfNotNull(reference)
            paths.firstNotNullOfOrNull { path ->
                val bitmap = path.substringAfterLast('.').lowercase() in setOf("png", "webp", "jpg", "jpeg")
                if (path.startsWith("res/") && bitmap) {
                    bytes(zip, path)
                } else null
            }
        } catch (_: RuntimeException) {
            // An optional icon must never prevent installing an otherwise valid extension.
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun bytes(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > MAX_BYTES) return null
        return zip.getInputStream(entry).use { it.readNBytes(MAX_BYTES + 1) }.takeIf { it.size <= MAX_BYTES }
    }

    private class Table(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val strings: List<String>
        private val values = mutableMapOf<Int, MutableList<Value>>()
        private val names = mutableMapOf<String, Int>()
        private data class Value(val density: Int, val type: Int, val data: Int)

        init {
            require(short(0) == 0x0002)
            var pool = emptyList<String>()
            chunks(0) { at ->
                when (short(at)) {
                    0x0001 -> pool = ApkManifestReader.readResourceStrings(buffer, at)
                    0x0200 -> readPackage(at)
                }
            }
            strings = pool
        }

        fun paths(reference: String): List<String> {
            val id = if (reference.startsWith("@0x")) reference.substring(3).toLong(16).toInt()
                else names[reference.removePrefix("@").substringAfter(':')] ?: return emptyList()
            return paths(id)
        }

        private fun paths(id: Int, seen: Set<Int> = emptySet()): List<String> {
            if (id in seen || seen.size >= 16) return emptyList()
            return values[id].orEmpty().sortedByDescending { it.density }.flatMap { value ->
                when (value.type) {
                    0x03 -> listOfNotNull(strings.getOrNull(value.data))
                    0x01 -> paths(value.data, seen + id)
                    else -> emptyList()
                }
            }
        }

        private fun readPackage(at: Int) {
            val packageId = int(at + 8)
            val typeOffset = if (short(at + 2) >= 288) int(at + 284) else 0
            val types = ApkManifestReader.readResourceStrings(buffer, at + int(at + 268))
            val keys = ApkManifestReader.readResourceStrings(buffer, at + int(at + 276))
            chunks(at) { type ->
                if (short(type) != 0x0201) return@chunks
                val typeId = (buffer.get(type + 8).toInt() and 0xff) + typeOffset
                val flags = buffer.get(type + 9).toInt() and 0xff
                val count = int(type + 12)
                val offsets = type + short(type + 2)
                val entries = type + int(type + 16)
                val end = type + int(type + 4)
                val density = if (short(type + 2) >= 36) short(type + 34) else 0
                require(count in 0..65536)
                repeat(count) { index ->
                    val entryIndex: Int
                    val offset: Int
                    when {
                        flags and 1 != 0 -> {
                            require(offsets + index * 4 + 4 <= entries)
                            entryIndex = short(offsets + index * 4)
                            offset = short(offsets + index * 4 + 2) * 4
                        }
                        flags and 2 != 0 -> {
                            require(offsets + index * 2 + 2 <= entries)
                            entryIndex = index
                            val value = short(offsets + index * 2)
                            offset = if (value == 0xffff) -1 else value * 4
                        }
                        else -> {
                            require(offsets + index * 4 + 4 <= entries)
                            entryIndex = index
                            offset = int(offsets + index * 4)
                        }
                    }
                    if (offset == -1) return@repeat
                    val entry = entries + offset
                    require(offset >= 0 && entry.toLong() + 8 <= end)
                    val entryFlags = short(entry + 2)
                    if (entryFlags and 1 != 0) return@repeat
                    val compact = entryFlags and 8 != 0
                    val value = entry + short(entry)
                    if (!compact) require(value.toLong() + 8 <= end)
                    val dataType = if (compact) entryFlags ushr 8 else buffer.get(value + 3).toInt() and 0xff
                    val data = int(if (compact) entry + 4 else value + 4)
                    val id = (packageId shl 24) or (typeId shl 16) or entryIndex
                    val key = if (compact) short(entry) else int(entry + 4)
                    val typeName = types.getOrNull(typeId - typeOffset - 1)
                    val keyName = keys.getOrNull(key)
                    if (typeName != null && keyName != null) names["$typeName/$keyName"] = id
                    values.getOrPut(id) { mutableListOf() }.add(Value(density, dataType, data))
                }
            }
        }

        private fun chunks(parent: Int, visit: (Int) -> Unit) {
            val end = parent.toLong() + int(parent + 4)
            require(end <= buffer.limit() && end >= parent + 8L)
            var at = parent + short(parent + 2)
            require(at >= parent + 8)
            while (at.toLong() + 8 <= end) {
                val size = int(at + 4)
                require(size >= 8 && short(at + 2) in 8..size && at.toLong() + size <= end)
                visit(at)
                at += size
            }
        }

        private fun int(at: Int): Int = buffer.getInt(at)
        private fun short(at: Int): Int = buffer.getShort(at).toInt() and 0xffff
    }
}

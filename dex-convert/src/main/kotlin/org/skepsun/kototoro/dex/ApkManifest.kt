package org.skepsun.kototoro.dex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile

class ApkManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The parts of an extension APK's manifest the hosts classify on. Attribute values that are resource references
 * (an app label such as `@string/app_name`) are not resolvable without the resource table and are left null.
 */
data class ApkManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String?,
    val features: Set<String>,
    val metaData: Map<String, String>,
) {
    /** The same data as the plain-text manifest the JVM extension inspectors read from Keiyoushi-style jars. */
    fun toText(): String = buildString {
        fun attr(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${attr(packageName)}" """)
        append("""android:versionCode="$versionCode" android:versionName="${attr(versionName)}">""").append('\n')
        features.forEach { append("""    <uses-feature android:name="${attr(it)}"/>""").append('\n') }
        append("""    <application android:label="${attr(label.orEmpty())}">""").append('\n')
        metaData.forEach { (name, value) ->
            append("""        <meta-data android:name="${attr(name)}" android:value="${attr(value)}"/>""").append('\n')
        }
        append("    </application>\n</manifest>\n")
    }
}

/** Reads the binary XML (AXML) AndroidManifest.xml of an APK; defensive about sizes, never executes anything. */
object ApkManifestReader {
    private const val MAXIMUM_BYTES = 4 * 1024 * 1024
    private const val MAXIMUM_STRINGS = 200_000

    fun read(apk: Path): ApkManifest {
        val bytes = try {
            ZipFile(apk.toFile()).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: throw ApkManifestException("The archive has no AndroidManifest.xml")
                if (entry.size > MAXIMUM_BYTES) throw ApkManifestException("AndroidManifest.xml is too large")
                zip.getInputStream(entry).use { it.readNBytes(MAXIMUM_BYTES + 1) }
            }
        } catch (error: java.io.IOException) {
            throw ApkManifestException("Not a readable APK", error)
        }
        if (bytes.size > MAXIMUM_BYTES) throw ApkManifestException("AndroidManifest.xml is too large")
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): ApkManifest {
        try {
            return Decoder(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).decode()
        } catch (error: ApkManifestException) {
            throw error
        } catch (error: RuntimeException) {
            // Out-of-range offsets of a malformed file surface as buffer/index exceptions.
            throw ApkManifestException("Malformed binary manifest", error)
        }
    }

    private const val RES_XML = 0x0003
    private const val STRING_POOL = 0x0001
    private const val RESOURCE_MAP = 0x0180
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_BOOLEAN = 0x12

    // Attribute resource ids of the android: namespace, for manifests whose pool keeps no attribute names.
    private val KNOWN_ATTRIBUTES = mapOf(
        0x01010001 to "label", 0x01010003 to "name", 0x01010024 to "value",
        0x0101021b to "versionCode", 0x0101021c to "versionName",
    )

    private class Decoder(private val buffer: ByteBuffer) {
        private var strings = emptyList<String>()
        private var resourceIds = IntArray(0)

        fun decode(): ApkManifest {
            if (buffer.limit() < 8 || (buffer.getShort(0).toInt() and 0xffff) != RES_XML) {
                throw ApkManifestException("Not a binary XML document")
            }
            var packageName: String? = null
            var versionCode = 0L
            var versionName: String? = null
            var label: String? = null
            val features = linkedSetOf<String>()
            val meta = linkedMapOf<String, String>()
            val path = ArrayDeque<String>()
            var offset = (buffer.getShort(2).toInt() and 0xffff)
            val end = minOf(buffer.limit().toLong(), buffer.getInt(4).toLong() and 0xffffffffL).toInt()
            while (offset + 8 <= end) {
                val type = buffer.getShort(offset).toInt() and 0xffff
                val header = buffer.getShort(offset + 2).toInt() and 0xffff
                val size = buffer.getInt(offset + 4).also { if (it < 8 || offset.toLong() + it > end) throw ApkManifestException("Bad chunk size") }
                when (type) {
                    STRING_POOL -> strings = readStrings(offset)
                    RESOURCE_MAP -> resourceIds = IntArray((size - header) / 4) { buffer.getInt(offset + header + it * 4) }
                    START_ELEMENT -> {
                        val body = offset + 16
                        val name = string(buffer.getInt(body + 4))
                        val attributeStart = buffer.getShort(body + 8).toInt() and 0xffff
                        val attributeSize = buffer.getShort(body + 10).toInt() and 0xffff
                        val count = buffer.getShort(body + 12).toInt() and 0xffff
                        val attributes = HashMap<String, String>()
                        for (index in 0 until count) {
                            val at = body + attributeStart + index * attributeSize
                            val attribute = attributeName(buffer.getInt(at + 4))
                            attributes[attribute] = value(at)
                        }
                        val parent = path.lastOrNull()
                        when {
                            name == "manifest" && parent == null -> {
                                packageName = attributes["package"]
                                versionCode = attributes["versionCode"]?.let(::number) ?: 0L
                                versionName = attributes["versionName"]
                            }
                            name == "uses-feature" && parent == "manifest" -> attributes["name"]?.let(features::add)
                            name == "application" && parent == "manifest" -> label = attributes["label"]?.takeUnless { it.startsWith("@") }
                            name == "meta-data" && parent == "application" -> {
                                val key = attributes["name"]
                                if (key != null && key !in meta) meta[key] = attributes["value"].orEmpty()
                            }
                        }
                        path.addLast(name)
                    }
                    END_ELEMENT -> if (path.isNotEmpty()) path.removeLast()
                }
                offset += size
            }
            return ApkManifest(
                packageName ?: throw ApkManifestException("The manifest has no package name"),
                versionCode, versionName.orEmpty(), label, features, meta,
            )
        }

        private fun readStrings(chunk: Int): List<String> {
            val count = buffer.getInt(chunk + 8)
            val flags = buffer.getInt(chunk + 16)
            val stringsStart = buffer.getInt(chunk + 20)
            val headerSize = buffer.getShort(chunk + 2).toInt() and 0xffff
            if (count < 0 || count > MAXIMUM_STRINGS) throw ApkManifestException("Bad string pool")
            val utf8 = flags and 0x100 != 0
            return List(count) { index ->
                val start = chunk + stringsStart + buffer.getInt(chunk + headerSize + index * 4)
                if (utf8) utf8(start) else utf16(start)
            }
        }

        private fun utf8(start: Int): String {
            var at = start
            // The character count comes first (unused here), then the byte count; both may take one or two bytes.
            at += if (buffer.get(at).toInt() and 0x80 != 0) 2 else 1
            val first = buffer.get(at).toInt() and 0xff
            val length = if (first and 0x80 != 0) (((first and 0x7f) shl 8) or (buffer.get(at + 1).toInt() and 0xff)).also { at += 2 }
                else first.also { at += 1 }
            val data = ByteArray(length).also { buffer.duplicate().position(at).get(it) }
            return String(data, StandardCharsets.UTF_8)
        }

        private fun utf16(start: Int): String {
            var length = buffer.getShort(start).toInt() and 0xffff
            var at = start + 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7fff) shl 16) or (buffer.getShort(at).toInt() and 0xffff)
                at += 2
            }
            val data = ByteArray(length * 2).also { buffer.duplicate().position(at).get(it) }
            return String(data, StandardCharsets.UTF_16LE)
        }

        private fun string(index: Int): String = if (index in strings.indices) strings[index] else ""

        private fun attributeName(index: Int): String {
            val named = string(index)
            if (named.isNotEmpty()) return named
            return resourceIds.getOrNull(index)?.let(KNOWN_ATTRIBUTES::get).orEmpty()
        }

        private fun value(at: Int): String {
            val raw = buffer.getInt(at + 8)
            val type = buffer.get(at + 15).toInt() and 0xff
            val data = buffer.getInt(at + 16)
            return when (type) {
                TYPE_STRING -> string(if (raw >= 0) raw else data)
                TYPE_INT_DEC -> data.toString()
                TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
                TYPE_BOOLEAN -> (data != 0).toString()
                TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
                TYPE_REFERENCE -> "@0x" + Integer.toHexString(data)
                else -> if (raw >= 0) string(raw) else data.toString()
            }
        }

        private fun number(text: String): Long = if (text.startsWith("0x")) text.substring(2).toLong(16) else text.toLong()
    }
}

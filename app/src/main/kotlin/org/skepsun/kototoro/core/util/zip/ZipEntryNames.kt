package org.skepsun.kototoro.core.util.zip

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap

/**
 * The entry names an archive's author wrote, recovered from the raw central directory (#682).
 *
 * A ZIP entry name is either flagged as UTF-8 or left in "whatever the writing system used", and the
 * spec's answer for the un-flagged case is IBM437. Chinese Windows tools write GBK instead, without
 * the flag. Every reader this app uses - `ZipFile`, `ZipInputStream` and okio's `openZip` - decodes
 * entry names as UTF-8 regardless of the flag, so a GBK archive lists `01 ����` where its author
 * wrote `01 春天`; the archive is fine, the reader guesses one charset for every archive it opens.
 *
 * This reads the raw bytes once and maps that unreadable name to the intended one. Names are mapped
 * per path component, because a component is exactly as long as its bytes make it: splitting on `/`
 * is safe for every charset considered here (GBK and Shift_JIS trail bytes are all above `/`).
 */
class ZipEntryNames private constructor(
    private val displayByArchive: Map<String, String>,
) {

    private val archiveByDisplay: Map<String, String> = LinkedHashMap<String, String>(displayByArchive.size).apply {
        for ((archiveName, displayName) in displayByArchive) {
            putIfAbsent(displayName, archiveName)
        }
    }

    /** Whether any name needed repairing; false for the ordinary UTF-8 archive. */
    val isEmpty: Boolean
        get() = displayByArchive.isEmpty()

    /** The name [archiveName] was meant to be, or [archiveName] itself when it is already readable. */
    fun display(archiveName: String): String = mapComponents(archiveName, displayByArchive)

    /** The name this archive's readers hold for [displayName]; the exact reverse of [display]. */
    fun archive(displayName: String): String = mapComponents(displayName, archiveByDisplay)

    companion object {

        /** Names that need no repair, shared so callers on the common path allocate nothing. */
        val EMPTY = ZipEntryNames(emptyMap())

        /**
         * UTF-8 first, because an un-flagged archive written by a tool that speaks UTF-8 anyway is the
         * other half of the bug and needs no repair; then the charsets a mojibake archive can plausibly
         * use - GBK for Chinese Windows, Shift_JIS for Japanese Windows, IBM437 for the spec's own answer.
         */
        private val CANDIDATES: List<Charset> = listOf("UTF-8", "GB18030", "Shift_JIS", "IBM437")
            .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }

        private val SPEC_DEFAULT: Charset? = CANDIDATES.lastOrNull()

        /** Reads [file]'s central directory; a missing, broken or non-ZIP file simply has no names to repair. */
        fun read(file: File): ZipEntryNames = runCatching {
            ZipFile.builder().setFile(file).get().use(::fromArchive)
        }.getOrDefault(EMPTY)

        /** The same for an archive behind a descriptor; [channel] is left open for its owner. */
        fun read(channel: SeekableByteChannel): ZipEntryNames = runCatching {
            ZipFile.builder().setSeekableByteChannel(NonClosingChannel(channel)).get().use(::fromArchive)
        }.getOrDefault(EMPTY)

        private fun fromArchive(zip: ZipFile): ZipEntryNames {
            // The flag is per entry, not per archive: a repacked or appended archive can mix flagged
            // UTF-8 names with un-flagged GBK ones, and repairing by archive would leave the latter
            // mojibake (#693). Flagged entries are already readable, so only their neighbours need repair.
            val components = zip.entries.asSequence()
                .filterNot { it.generalPurposeBit.usesUTF8ForNames() }
                .mapNotNull { it.rawName }
                .flatMap { rawName -> splitComponents(rawName).asSequence() }
                .filter { raw -> raw.any { (it.toInt() and 0x80) != 0 } }
                .distinctBy { raw -> raw.asList() }
                .toList()
            if (components.isEmpty()) {
                return EMPTY
            }
            val charset = detect(components) ?: return EMPTY
            if (charset == Charsets.UTF_8) {
                // Un-flagged but already UTF-8: readers show the right names, nothing to repair.
                return EMPTY
            }
            val displayByArchive = LinkedHashMap<String, String>(components.size)
            for (raw in components) {
                val displayName = decode(raw, charset) ?: continue
                val archiveName = String(raw, Charsets.UTF_8)
                if (archiveName != displayName) {
                    displayByArchive.putIfAbsent(archiveName, displayName)
                }
            }
            return if (displayByArchive.isEmpty()) EMPTY else ZipEntryNames(displayByArchive)
        }

        /**
         * The first candidate that decodes every component strictly. The CJK charsets have to produce
         * actual CJK text: a Latin-1 name decodes as GBK bytes just often enough that accepting it blind
         * would turn `Café` into a Chinese pair. IBM437 accepts everything by definition, so it closes.
         */
        private fun detect(components: List<ByteArray>): Charset? = CANDIDATES.firstOrNull { charset ->
            val decoded = components.map { raw -> decode(raw, charset) ?: return@firstOrNull false }
            when {
                charset == Charsets.UTF_8 -> true
                charset == SPEC_DEFAULT -> true
                else -> decoded.any { name -> name.any(::isCjk) }
            }
        }

        private fun isCjk(char: Char): Boolean =
            char in '\u2E80'..'\u9FFF' || char in '\uFF00'..'\uFFEF'

        private fun decode(raw: ByteArray, charset: Charset): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        /** One raw entry name split on `/`, so each path component stands on its own. */
        private fun splitComponents(rawName: ByteArray): List<ByteArray> {
            val components = ArrayList<ByteArray>(4)
            var start = 0
            for (index in rawName.indices) {
                if (rawName[index] == SLASH) {
                    if (index > start) {
                        components += rawName.copyOfRange(start, index)
                    }
                    start = index + 1
                }
            }
            if (start < rawName.size) {
                components += rawName.copyOfRange(start, rawName.size)
            }
            return components
        }

        private fun mapComponents(name: String, map: Map<String, String>): String {
            if (map.isEmpty()) {
                return name
            }
            map[name]?.let { return it }
            val builder = StringBuilder(name.length + 8)
            var start = 0
            while (true) {
                val end = name.indexOf(PATH_SEPARATOR, start).let { if (it == -1) name.length else it }
                val component = name.substring(start, end)
                builder.append(map[component] ?: component)
                if (end == name.length) {
                    return builder.toString()
                }
                builder.append(PATH_SEPARATOR)
                start = end + 1
            }
        }

        private const val SLASH = '/'.code.toByte()
        private const val PATH_SEPARATOR = '/'
    }
}

/**
 * Keeps the archive's owner in charge of its channel: the central directory reader closes what it
 * is given, and a `content://` descriptor outlives a single name lookup.
 */
private class NonClosingChannel(private val delegate: SeekableByteChannel) : SeekableByteChannel {

    override fun read(dst: ByteBuffer): Int = delegate.read(dst)

    override fun write(src: ByteBuffer): Int = throw IOException("read-only")

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): SeekableByteChannel = apply { delegate.position(newPosition) }

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): SeekableByteChannel = throw IOException("read-only")

    override fun isOpen(): Boolean = delegate.isOpen

    override fun close() = Unit
}

@file:OptIn(ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.cinterop.*
import platform.posix.*
import platform.zlib.*

/**
 * Minimal ZIP reader/writer for Kototoro/Kotatsu backups (flat names, STORED/DEFLATE, no ZIP64 or encryption).
 * Android writes them with `ZipOutputStream`, so data descriptors and central-directory sizes are expected.
 */
internal class BackupZipEntry(
    val name: String,
    val method: Int,
    val crc: UInt,
    val compressedSize: Long,
    val size: Long,
    val dataOffset: Long,
)

internal class BackupZipReader private constructor(private val bytes: ByteArray, val entries: List<BackupZipEntry>) {

    /** Inflates and CRC-checks one entry; a mismatch rejects the whole archive. */
    fun read(entry: BackupZipEntry): ByteArray {
        require(entry.size <= MAX_ENTRY_BYTES) { localized("备份单节超过 256 MiB 限制") }
        val end = entry.dataOffset + entry.compressedSize
        require(entry.dataOffset >= 0 && end <= bytes.size) { localized("备份 ZIP 数据越界") }
        val output = when (entry.method) {
            0 -> bytes.copyOfRange(entry.dataOffset.toInt(), end.toInt())
            8 -> inflate(bytes, entry.dataOffset.toInt(), entry.compressedSize.toInt(), entry.size)
            else -> throw IllegalArgumentException(localized("备份 ZIP 使用了不支持的压缩方式"))
        }
        require(output.size.toLong() == entry.size && crc(output) == entry.crc) { localized("备份 ZIP 校验失败：%@", entry.name) }
        return output
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

        fun open(path: String): BackupZipReader = parse(readFile(path, MAX_ARCHIVE_BYTES), strict = true)

        /**
         * [strict] is Kototoro's own format: flat section names, at most 256 entries, every payload verified up
         * front. Other apps' archives (Venera) nest folders and carry more files; only the entries read are checked.
         */
        fun parse(bytes: ByteArray, strict: Boolean): BackupZipReader {
            val eocd = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)).firstOrNull {
                bytes.u32(it) == 0x06054b50u
            } ?: throw IllegalArgumentException(localized("所选文件不是有效的 ZIP 备份"))
            val count = bytes.u16(eocd + 10)
            val directorySize = bytes.u32(eocd + 12).toLong()
            val directoryOffset = bytes.u32(eocd + 16).toLong()
            require(bytes.u16(eocd + 4) == 0 && bytes.u16(eocd + 6) == 0 && count != 0xffff &&
                directoryOffset != 0xffffffffL && directoryOffset + directorySize <= eocd) { localized("备份 ZIP 目录无效") }
            require(count <= if (strict) 256 else 65534) { localized("备份包含过多条目") }
            val names = hashSetOf<String>()
            var expanded = 0L
            var position = directoryOffset.toInt()
            val entries = List(count) {
                require(position + 46 <= eocd && bytes.u32(position) == 0x02014b50u) { localized("备份 ZIP 目录无效") }
                val flags = bytes.u16(position + 8)
                val method = bytes.u16(position + 10)
                val crc = bytes.u32(position + 16)
                val compressed = bytes.u32(position + 20).toLong()
                val size = bytes.u32(position + 24).toLong()
                val nameLength = bytes.u16(position + 28)
                val extraLength = bytes.u16(position + 30)
                val commentLength = bytes.u16(position + 32)
                val local = bytes.u32(position + 42).toLong()
                require(position + 46 + nameLength <= eocd) { localized("备份 ZIP 目录无效") }
                val name = bytes.decodeToString(position + 46, position + 46 + nameLength)
                require(flags and 1 == 0) { localized("不支持加密的备份") }
                require(compressed != 0xffffffffL && size != 0xffffffffL && local != 0xffffffffL) { localized("不支持 ZIP64 备份") }
                require(!strict || name.isNotEmpty() && name.length <= 128 && '/' !in name && '\\' !in name &&
                    names.add(name.lowercase())) { localized("备份包含重复或无效的节名") }
                require(!strict || size <= MAX_ENTRY_BYTES) { localized("备份单节超过 256 MiB 限制") }
                if (strict) expanded += size
                require(expanded <= MAX_EXPANDED_BYTES) { localized("备份解压数据超过 1 GiB 限制") }
                require(local + 30 <= bytes.size && bytes.u32(local.toInt()) == 0x04034b50u) { localized("备份 ZIP 本地头无效") }
                val dataOffset = local + 30 + bytes.u16(local.toInt() + 26) + bytes.u16(local.toInt() + 28)
                position += 46 + nameLength + extraLength + commentLength
                BackupZipEntry(name, method, crc, compressed, size, dataOffset)
            }
            val reader = BackupZipReader(bytes, entries)
            // Like the desktop reader, verify every payload before the preview is shown.
            if (strict) entries.forEach { reader.read(it) }
            return reader
        }
    }
}

/** Streams sections with data descriptors; the file appears at [target] only after a complete write. */
internal class BackupZipWriter(private val target: String) : AutoCloseable {
    private val temporary = "$target.partial"
    private val file = fopen(temporary, "wb") ?: throw IllegalStateException(localized("无法创建备份文件"))
    private var offset = 0L
    private val central = mutableListOf<ByteArray>()
    private var stream: CPointer<z_stream>? = null
    private var entryName = ""
    private var entryOffset = 0L
    private var entryCrc = 0uL
    private var entrySize = 0L
    private var entryCompressed = 0L
    private var finished = false
    private val input = ByteArray(65536)
    private val output = ByteArray(65536)
    private var pending = 0

    fun begin(name: String) {
        check(stream == null)
        entryName = name; entryOffset = offset; entryCrc = 0uL; entrySize = 0; entryCompressed = 0; pending = 0
        val encoded = name.encodeToByteArray()
        emit(ByteBuilder().u32(0x04034b50u).u16(20).u16(0x0808).u16(8).u16(0).u16(0x21)
            .u32(0u).u32(0u).u32(0u).u16(encoded.size).u16(0).bytes(encoded).build())
        val created = nativeHeap.alloc<z_stream>()
        memset(created.ptr, 0, sizeOf<z_stream>().convert())
        check(deflateInit2_(created.ptr, 6, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION,
            sizeOf<z_stream>().toInt()) == Z_OK) { localized("无法初始化压缩") }
        stream = created.ptr
    }

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        entrySize += data.size
        require(entrySize <= BackupZipReader.MAX_ENTRY_BYTES) { localized("备份数据超过大小限制，未生成备份文件") }
        data.usePinned { entryCrc = crc32(entryCrc, it.addressOf(0).reinterpret(), data.size.convert()) }
        var copied = 0
        while (copied < data.size) {
            val chunk = minOf(data.size - copied, input.size - pending)
            data.copyInto(input, pending, copied, copied + chunk)
            pending += chunk; copied += chunk
            if (pending == input.size) deflate(Z_NO_FLUSH)
        }
    }

    fun end() {
        deflate(Z_FINISH)
        val active = checkNotNull(stream)
        deflateEnd(active); nativeHeap.free(active.pointed); stream = null
        val crc = entryCrc.toUInt()
        emit(ByteBuilder().u32(0x08074b50u).u32(crc).u32(entryCompressed.toUInt()).u32(entrySize.toUInt()).build())
        val name = entryName.encodeToByteArray()
        central += ByteBuilder().u32(0x02014b50u).u16(20).u16(20).u16(0x0808).u16(8).u16(0).u16(0x21)
            .u32(crc).u32(entryCompressed.toUInt()).u32(entrySize.toUInt()).u16(name.size).u16(0).u16(0).u16(0).u16(0)
            .u32(0u).u32(entryOffset.toUInt()).bytes(name).build()
    }

    fun finish() {
        check(stream == null)
        val start = offset
        central.forEach(::emit)
        emit(ByteBuilder().u32(0x06054b50u).u16(0).u16(0).u16(central.size).u16(central.size)
            .u32((offset - start).toUInt()).u32(start.toUInt()).u16(0).build())
        check(fflush(file) == 0 && fclose(file) == 0) { localized("无法写入备份文件") }
        finished = true
        require(access(target, F_OK) != 0) { localized("目标文件已存在，请选择新的备份文件名") }
        check(rename(temporary, target) == 0) { localized("无法保存备份文件") }
    }

    override fun close() {
        stream?.let { deflateEnd(it); nativeHeap.free(it.pointed); stream = null }
        if (!finished) { fclose(file); finished = true }
        remove(temporary)
    }

    /** Compresses the buffered input; small JSON fragments are batched rather than deflated one by one. */
    private fun deflate(flush: Int) {
        val active = checkNotNull(stream).pointed
        input.usePinned { source -> output.usePinned { out ->
            active.next_in = if (pending == 0) null else source.addressOf(0).reinterpret()
            active.avail_in = pending.convert()
            while (true) {
                active.next_out = out.addressOf(0).reinterpret()
                active.avail_out = output.size.convert()
                val result = deflate(active.ptr, flush)
                check(result == Z_OK || result == Z_STREAM_END || result == Z_BUF_ERROR) { localized("压缩备份失败") }
                val produced = output.size - active.avail_out.toInt()
                if (produced > 0) { emit(output.copyOf(produced)); entryCompressed += produced }
                if (flush == Z_FINISH) { if (result == Z_STREAM_END) break }
                else if (active.avail_in == 0u && active.avail_out != 0u) break
            }
        } }
        pending = 0
    }

    private fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        offset += bytes.size
        require(offset <= BackupZipReader.MAX_ARCHIVE_BYTES) { localized("备份数据超过大小限制，未生成备份文件") }
        val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
        check(written.toLong() == bytes.size.toLong()) { localized("无法写入备份文件") }
    }
}

private class ByteBuilder {
    private val out = ArrayList<Byte>()
    fun u16(value: Int) = apply { out += value.toByte(); out += (value shr 8).toByte() }
    fun u32(value: UInt) = apply { repeat(4) { out += (value shr (it * 8)).toByte() } }
    fun bytes(value: ByteArray) = apply { value.forEach { out += it } }
    fun build() = out.toByteArray()
}

private fun ByteArray.u16(at: Int): Int {
    require(at >= 0 && at + 2 <= size) { localized("备份 ZIP 数据越界") }
    return (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.u32(at: Int): UInt {
    require(at >= 0 && at + 4 <= size) { localized("备份 ZIP 数据越界") }
    return (0 until 4).fold(0u) { acc, i -> acc or ((this[at + i].toUInt() and 0xffu) shl (i * 8)) }
}

private fun crc(data: ByteArray): UInt = if (data.isEmpty()) 0u else
    data.usePinned { crc32(0u, it.addressOf(0).reinterpret(), data.size.convert()).toUInt() }

private fun inflate(source: ByteArray, offset: Int, length: Int, expected: Long): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    memset(stream.ptr, 0, sizeOf<z_stream>().convert())
    check(inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) { localized("无法初始化解压") }
    val output = ByteArray(expected.toInt())
    try {
        source.usePinned { input ->
            stream.next_in = if (length == 0) null else input.addressOf(offset).reinterpret()
            stream.avail_in = length.convert()
            // One spare byte detects payloads larger than the declared size.
            val spare = ByteArray(1)
            if (output.isNotEmpty()) output.usePinned { out ->
                stream.next_out = out.addressOf(0).reinterpret()
                stream.avail_out = output.size.convert()
                val result = inflate(stream.ptr, Z_FINISH)
                require(result == Z_STREAM_END || result == Z_BUF_ERROR || result == Z_OK) { localized("备份 ZIP 解压失败") }
                require(stream.avail_out == 0u) { localized("备份 ZIP 解压长度不一致") }
            }
            spare.usePinned { out ->
                stream.next_out = out.addressOf(0).reinterpret()
                stream.avail_out = 1u
                val result = inflate(stream.ptr, Z_FINISH)
                require(result == Z_STREAM_END && stream.avail_out == 1u) { localized("备份 ZIP 解压长度不一致") }
            }
        }
    } finally { inflateEnd(stream.ptr) }
    output
}

internal fun readFile(path: String, limit: Long): ByteArray {
    val file = fopen(path, "rb") ?: throw IllegalArgumentException(localized("无法读取所选备份文件"))
    try {
        check(fseeko(file, 0, SEEK_END) == 0)
        val length = ftello(file)
        require(length in 22..limit) { if (length > limit) localized("备份压缩文件超过 256 MiB 限制") else localized("所选文件不是有效的 ZIP 备份") }
        check(fseeko(file, 0, SEEK_SET) == 0)
        val bytes = ByteArray(length.toInt())
        val read = bytes.usePinned { fread(it.addressOf(0), 1u, bytes.size.convert(), file) }
        check(read.toLong() == length) { localized("无法读取所选备份文件") }
        return bytes
    } finally { fclose(file) }
}

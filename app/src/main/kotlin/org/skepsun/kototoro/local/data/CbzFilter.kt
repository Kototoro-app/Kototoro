package org.skepsun.kototoro.local.data

import java.io.File
import java.io.InputStream

private fun isZipExtension(ext: String?): Boolean {
    return ext.equals("cbz", ignoreCase = true) ||
        ext.equals("zip", ignoreCase = true) ||
        ext.equals("epub", ignoreCase = true)
}

fun hasZipExtension(string: String): Boolean {
    val ext = string.substringAfterLast('.', "")
    return isZipExtension(ext)
}

fun isZipMimeType(mimeType: String?): Boolean {
    val normalized = mimeType?.substringBefore(';')?.trim() ?: return false
    return normalized.equals("application/vnd.comicbook+zip", ignoreCase = true) ||
        normalized.equals("application/x-cbz", ignoreCase = true) ||
        normalized.equals("application/zip", ignoreCase = true) ||
        normalized.equals("application/x-zip-compressed", ignoreCase = true)
}

fun hasZipMagicBytes(header: ByteArray, length: Int = header.size): Boolean {
    if (length < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
        return false
    }
    return (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
        (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
        (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
}

fun File.hasZipSignature(): Boolean {
    if (!isFile || length() < 4L) {
        return false
    }
    return runCatching {
        inputStream().use(InputStream::hasZipSignature)
    }.getOrDefault(false)
}

internal fun InputStream.hasZipSignature(): Boolean {
    val header = ByteArray(4)
    var offset = 0
    while (offset < header.size) {
        val read = read(header, offset, header.size - offset)
        if (read <= 0) {
            break
        }
        offset += read
    }
    return hasZipMagicBytes(header, offset)
}

val File.isZipArchive: Boolean
    get() = isFile && (isZipExtension(extension) || (extension.isEmpty() && hasZipSignature()))

package org.skepsun.kototoro.backups.external

import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okio.gzip
import org.skepsun.kototoro.parsers.model.ContentType
import kotlin.math.max

private const val MAGIC_GZIP = 0x1f8b
private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d
private const val MAGIC_JSON_SIGNATURE2 = 0x7b22
private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a

private const val WIRE_TYPE_VARINT = 0
private const val WIRE_TYPE_I64 = 1
private const val WIRE_TYPE_LEN = 2
private const val WIRE_TYPE_SGROUP = 3
private const val WIRE_TYPE_EGROUP = 4
private const val WIRE_TYPE_I32 = 5

private const val FIELD_BACKUP_MANGA = 1
private const val FIELD_BACKUP_CATEGORIES = 2
private const val FIELD_BACKUP_SOURCES = 101
private const val FIELD_BACKUP_ANIME_SOURCES = 103
private const val FIELD_BACKUP_ANIME = 501

private const val MAX_GROUP_DEPTH = 32

/** Default maximum total decompressed bytes accepted from an external backup stream (256 MiB). */
const val DEFAULT_MAX_EXTERNAL_BACKUP_BYTES: Long = 256L * 1024 * 1024

/** Default maximum bytes accepted for a single top-level protobuf record (16 MiB). */
const val DEFAULT_MAX_EXTERNAL_BACKUP_RECORD_BYTES: Long = 16L * 1024 * 1024

data class DecodedExternalBackup(
    val app: ExternalBackupApp,
    val payload: ExternalBackupPayload,
)

/**
 * Reads the raw bytes of an external backup, transparently un-gzipping it and enforcing a
 * decompressed ceiling of [maxBytes].
 * JSON backups are rejected with [UnsupportedExternalBackupException]; oversized streams fail fast
 * with [ExternalBackupTooLargeException].
 * Takes ownership of [source] and closes it on success or failure.
 */
fun readExternalBackupBytes(
    source: BufferedSource,
    maxBytes: Long = DEFAULT_MAX_EXTERNAL_BACKUP_BYTES,
): ByteArray {
    var decoded = source
    try {
        decoded = openExternalBackupSource(source, maxBytes)
        return decoded.readByteArray()
    } finally {
        decoded.close()
    }
}

/**
 * Streams top-level protobuf records directly from [source] (transparently un-gzipping it) without
 * buffering the entire decompressed archive or full object graph in memory.
 *
 * Returns null when [app] is [ExternalBackupApp.VENERA] or when the protobuf wire stream is malformed.
 * Rejects JSON backups with [UnsupportedExternalBackupException], oversized streams/records with
 * [ExternalBackupTooLargeException], and propagates underlying I/O or gzip errors.
 * Takes ownership of [source] and closes it on success or failure.
 */
fun decodeMihonOrAniyomiBackup(
    source: BufferedSource,
    app: ExternalBackupApp,
    maxBytes: Long = DEFAULT_MAX_EXTERNAL_BACKUP_BYTES,
    maxRecordBytes: Long = DEFAULT_MAX_EXTERNAL_BACKUP_RECORD_BYTES,
): ExternalBackupPayload? {
    var decoded = source
    try {
        if (app == ExternalBackupApp.VENERA) {
            return null
        }
        decoded = openExternalBackupSource(source, maxBytes)
        val mode = when (app) {
            ExternalBackupApp.MIHON -> ProtobufDecodeMode.MIHON
            ExternalBackupApp.ANIYOMI -> ProtobufDecodeMode.ANIYOMI
            ExternalBackupApp.VENERA -> return null
        }
        return decodeMihonOrAniyomiProtobuf(decoded, mode, maxRecordBytes)?.payload
    } finally {
        decoded.close()
    }
}

/**
 * Streams a Mihon or Aniyomi backup from [source] in a single pass, detecting [ExternalBackupApp.ANIYOMI]
 * when anime or anime-source fields are present and [ExternalBackupApp.MIHON] otherwise.
 * Takes ownership of [source] and closes it on success or failure.
 */
fun decodeMihonOrAniyomiBackupAutoDetect(
    source: BufferedSource,
    maxBytes: Long = DEFAULT_MAX_EXTERNAL_BACKUP_BYTES,
    maxRecordBytes: Long = DEFAULT_MAX_EXTERNAL_BACKUP_RECORD_BYTES,
): DecodedExternalBackup? {
    var decoded = source
    try {
        decoded = openExternalBackupSource(source, maxBytes)
        return decodeMihonOrAniyomiProtobuf(decoded, ProtobufDecodeMode.AUTO_DETECT, maxRecordBytes)
    } finally {
        decoded.close()
    }
}

/**
 * Decodes bytes using the selected Mihon or Aniyomi protobuf schema. Returns null when decoding fails.
 * Venera backups are SQLite based and are decoded by the Android-only decoder.
 */
fun decodeMihonOrAniyomiBackup(
    bytes: ByteArray,
    app: ExternalBackupApp,
    maxRecordBytes: Long = DEFAULT_MAX_EXTERNAL_BACKUP_RECORD_BYTES,
): ExternalBackupPayload? {
    val mode = when (app) {
        ExternalBackupApp.MIHON -> ProtobufDecodeMode.MIHON
        ExternalBackupApp.ANIYOMI -> ProtobufDecodeMode.ANIYOMI
        ExternalBackupApp.VENERA -> return null
    }
    return decodeMihonOrAniyomiProtobuf(Buffer().write(bytes), mode, maxRecordBytes)?.payload
}

private enum class ProtobufDecodeMode {
    MIHON,
    ANIYOMI,
    AUTO_DETECT,
}

private class PendingContentRecord(
    val sourceId: Long,
    val record: ExternalBackupContentRecord,
)

private class ProtobufWireFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun openExternalBackupSource(
    source: BufferedSource,
    maxBytes: Long,
): BufferedSource {
    require(maxBytes >= 0L) { "maxBytes must be non-negative" }
    val peeked = source.peek().apply { require(2) }
    val magic = peeked.readShort().toInt() and 0xFFFF
    val rawOrGzip: Source = when (magic) {
        MAGIC_GZIP -> source.gzip()
        MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
            throw UnsupportedExternalBackupException("JSON backups are not supported")
        }
        else -> source
    }
    return ByteLimitedSource(rawOrGzip, maxBytes).buffer()
}

private class ByteLimitedSource(
    private val delegate: Source,
    private val maxBytes: Long,
) : Source {
    private var bytesRead = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount <= 0L) return 0L
        val remaining = maxBytes - bytesRead
        val limit = if (remaining >= byteCount) byteCount else remaining + 1L
        val count = delegate.read(sink, limit)
        if (count == -1L) return -1L
        bytesRead += count
        if (bytesRead > maxBytes) {
            throw ExternalBackupTooLargeException(
                "External backup exceeds maximum allowed decompressed size of $maxBytes bytes",
            )
        }
        return count
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.close()
}

private fun decodeMihonOrAniyomiProtobuf(
    source: BufferedSource,
    mode: ProtobufDecodeMode,
    maxRecordBytes: Long,
): DecodedExternalBackup? {
    require(maxRecordBytes >= 0L) { "maxRecordBytes must be non-negative" }
    val acceptAnimeFields = mode != ProtobufDecodeMode.MIHON
    val provisionalApp = if (mode == ProtobufDecodeMode.ANIYOMI) {
        ExternalBackupApp.ANIYOMI
    } else {
        ExternalBackupApp.MIHON
    }
    val mangaRecords = ArrayList<PendingContentRecord>()
    val animeRecords = ArrayList<PendingContentRecord>()
    val categories = ArrayList<MihonBackupCategory>()
    val mangaSourceNames = HashMap<Long, String>()
    val animeSourceNames = HashMap<Long, String>()
    var hasAniyomiFields = false

    try {
        while (!source.exhausted()) {
            val tag = source.readRawVarint64()
            if (tag <= 0L || tag > UInt.MAX_VALUE.toLong()) {
                throw ProtobufWireFormatException("Invalid protobuf tag: $tag")
            }
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (fieldNumber <= 0) {
                throw ProtobufWireFormatException("Invalid protobuf field number: $fieldNumber")
            }

            when (fieldNumber) {
                FIELD_BACKUP_MANGA -> {
                    val recordBytes = source.readKnownRecordBytes(fieldNumber, wireType, maxRecordBytes)
                    if (mode == ProtobufDecodeMode.ANIYOMI) {
                        val manga = decodeRecord {
                            ProtoBuf.decodeFromByteArray(AniyomiBackupManga.serializer(), recordBytes)
                        }
                        manga.toRecord(
                            app = ExternalBackupApp.ANIYOMI,
                            sourceName = "MIHON_${manga.source}",
                            contentType = ContentType.MANGA,
                            totalCount = manga.chapters.size,
                            completedCount = manga.chapters.count { it.read },
                            sourceDisplayName = null,
                        )?.let { mangaRecords.add(PendingContentRecord(manga.source, it)) }
                    } else {
                        val manga = decodeRecord {
                            ProtoBuf.decodeFromByteArray(MihonBackupManga.serializer(), recordBytes)
                        }
                        manga.toRecord(
                            app = provisionalApp,
                            sourceName = "MIHON_${manga.source}",
                            contentType = ContentType.MANGA,
                            totalCount = manga.chapters.size,
                            completedCount = manga.chapters.count { it.read },
                            sourceDisplayName = null,
                        )?.let { mangaRecords.add(PendingContentRecord(manga.source, it)) }
                    }
                }

                FIELD_BACKUP_CATEGORIES -> {
                    val recordBytes = source.readKnownRecordBytes(fieldNumber, wireType, maxRecordBytes)
                    categories.add(
                        decodeRecord {
                            ProtoBuf.decodeFromByteArray(MihonBackupCategory.serializer(), recordBytes)
                        },
                    )
                }

                FIELD_BACKUP_SOURCES -> {
                    val recordBytes = source.readKnownRecordBytes(fieldNumber, wireType, maxRecordBytes)
                    val backupSource = decodeRecord {
                        ProtoBuf.decodeFromByteArray(MihonBackupSource.serializer(), recordBytes)
                    }
                    if (backupSource.sourceId != 0L && backupSource.name.isNotBlank()) {
                        mangaSourceNames[backupSource.sourceId] = backupSource.name
                    }
                }

                FIELD_BACKUP_ANIME_SOURCES -> if (acceptAnimeFields) {
                    val recordBytes = source.readKnownRecordBytes(fieldNumber, wireType, maxRecordBytes)
                    val backupSource = decodeRecord {
                        ProtoBuf.decodeFromByteArray(MihonBackupSource.serializer(), recordBytes)
                    }
                    hasAniyomiFields = true
                    if (backupSource.sourceId != 0L && backupSource.name.isNotBlank()) {
                        animeSourceNames[backupSource.sourceId] = backupSource.name
                    }
                } else {
                    source.skipField(wireType, fieldNumber, depth = 0)
                }

                FIELD_BACKUP_ANIME -> if (acceptAnimeFields) {
                    val recordBytes = source.readKnownRecordBytes(fieldNumber, wireType, maxRecordBytes)
                    val anime = decodeRecord {
                        ProtoBuf.decodeFromByteArray(AniyomiBackupAnime.serializer(), recordBytes)
                    }
                    hasAniyomiFields = true
                    anime.toRecord(
                        app = ExternalBackupApp.ANIYOMI,
                        sourceDisplayName = null,
                    )?.let { animeRecords.add(PendingContentRecord(anime.source, it)) }
                } else {
                    source.skipField(wireType, fieldNumber, depth = 0)
                }

                else -> source.skipField(wireType, fieldNumber, depth = 0)
            }
        }
    } catch (_: ProtobufWireFormatException) {
        return null
    }

    val resolvedApp = when (mode) {
        ProtobufDecodeMode.MIHON -> ExternalBackupApp.MIHON
        ProtobufDecodeMode.ANIYOMI -> ExternalBackupApp.ANIYOMI
        ProtobufDecodeMode.AUTO_DETECT -> if (hasAniyomiFields) ExternalBackupApp.ANIYOMI else ExternalBackupApp.MIHON
    }
    val sourceNamesById = if (resolvedApp == ExternalBackupApp.ANIYOMI && animeSourceNames.isNotEmpty()) {
        HashMap<Long, String>(mangaSourceNames.size + animeSourceNames.size).apply {
            putAll(mangaSourceNames)
            putAll(animeSourceNames)
        }
    } else {
        mangaSourceNames
    }

    val records = ArrayList<ExternalBackupContentRecord>(
        mangaRecords.size + if (resolvedApp == ExternalBackupApp.ANIYOMI) animeRecords.size else 0,
    )
    for (pending in mangaRecords) {
        val displayName = sourceNamesById[pending.sourceId]
        val record = pending.record
        records.add(
            if (record.app == resolvedApp && record.sourceDisplayName == displayName) {
                record
            } else {
                record.copy(app = resolvedApp, sourceDisplayName = displayName)
            },
        )
    }
    if (resolvedApp == ExternalBackupApp.ANIYOMI) {
        for (pending in animeRecords) {
            val displayName = sourceNamesById[pending.sourceId]
            val record = pending.record
            records.add(
                if (record.sourceDisplayName == displayName) {
                    record
                } else {
                    record.copy(sourceDisplayName = displayName)
                },
            )
        }
    }

    return DecodedExternalBackup(
        app = resolvedApp,
        payload = ExternalBackupPayload(
            records = records,
            favoriteCategories = categories.toFavoriteCategoryRecords(),
        ),
    )
}

private inline fun <T> decodeRecord(block: () -> T): T = try {
    block()
} catch (e: SerializationException) {
    throw ProtobufWireFormatException("Failed to decode protobuf record", e)
} catch (e: IllegalArgumentException) {
    throw ProtobufWireFormatException("Failed to decode protobuf record", e)
} catch (e: IndexOutOfBoundsException) {
    throw ProtobufWireFormatException("Failed to decode protobuf record", e)
}

private fun BufferedSource.readKnownRecordBytes(
    fieldNumber: Int,
    wireType: Int,
    maxRecordBytes: Long,
): ByteArray {
    if (wireType != WIRE_TYPE_LEN) {
        throw ProtobufWireFormatException("Expected length-delimited wire type for field $fieldNumber, got $wireType")
    }
    val length = readRawVarint64()
    if (length < 0L) {
        throw ProtobufWireFormatException("Negative length-delimited field length: $length")
    }
    if (length > maxRecordBytes || length > Int.MAX_VALUE.toLong()) {
        throw ExternalBackupTooLargeException(
            "External backup record for field $fieldNumber exceeds maximum allowed size of $maxRecordBytes bytes (declared $length bytes)",
        )
    }
    return readExactBytesOrWireError(length.toInt())
}

private fun BufferedSource.readRawVarint64(): Long {
    var result = 0L
    var shift = 0
    while (shift < 64) {
        if (exhausted()) throw ProtobufWireFormatException("Truncated varint")
        val b = readByte().toInt()
        if (shift == 63 && (b and 0xFE) != 0) {
            throw ProtobufWireFormatException("Malformed varint (64-bit overflow)")
        }
        result = result or ((b and 0x7F).toLong() shl shift)
        if ((b and 0x80) == 0) {
            return result
        }
        shift += 7
    }
    throw ProtobufWireFormatException("Malformed varint (exceeds 10 bytes)")
}

private fun BufferedSource.readExactBytesOrWireError(byteCount: Int): ByteArray {
    if (byteCount == 0) return ByteArray(0)
    val out = Buffer()
    var remaining = byteCount.toLong()
    while (remaining > 0L) {
        val read = read(out, remaining)
        if (read == -1L) {
            throw ProtobufWireFormatException("Truncated length-delimited field: expected $byteCount bytes")
        }
        remaining -= read
    }
    return out.readByteArray()
}

private fun BufferedSource.skipExactOrWireError(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0L) {
        if (exhausted()) throw ProtobufWireFormatException("Truncated protobuf field")
        val toSkip = minOf(remaining, buffer.size)
        skip(toSkip)
        remaining -= toSkip
    }
}

private fun BufferedSource.skipField(wireType: Int, fieldNumber: Int, depth: Int) {
    when (wireType) {
        WIRE_TYPE_VARINT -> readRawVarint64()
        WIRE_TYPE_I64 -> skipExactOrWireError(8L)
        WIRE_TYPE_LEN -> {
            val length = readRawVarint64()
            if (length < 0L) {
                throw ProtobufWireFormatException("Negative length-delimited field length: $length")
            }
            skipExactOrWireError(length)
        }
        WIRE_TYPE_SGROUP -> {
            if (depth >= MAX_GROUP_DEPTH) {
                throw ProtobufWireFormatException("Protobuf group nesting depth exceeded")
            }
            skipGroup(endFieldNumber = fieldNumber, depth = depth + 1)
        }
        WIRE_TYPE_EGROUP -> throw ProtobufWireFormatException("Unexpected protobuf end-group wire type")
        WIRE_TYPE_I32 -> skipExactOrWireError(4L)
        else -> throw ProtobufWireFormatException("Invalid protobuf wire type: $wireType")
    }
}

private fun BufferedSource.skipGroup(endFieldNumber: Int, depth: Int) {
    while (true) {
        if (exhausted()) {
            throw ProtobufWireFormatException("Unterminated protobuf group for field $endFieldNumber")
        }
        val tag = readRawVarint64()
        if (tag <= 0L || tag > UInt.MAX_VALUE.toLong()) {
            throw ProtobufWireFormatException("Invalid protobuf tag: $tag")
        }
        val innerFieldNumber = (tag ushr 3).toInt()
        val innerWireType = (tag and 0x7L).toInt()
        if (innerFieldNumber <= 0) {
            throw ProtobufWireFormatException("Invalid protobuf field number: $innerFieldNumber")
        }
        if (innerWireType == WIRE_TYPE_EGROUP) {
            if (innerFieldNumber != endFieldNumber) {
                throw ProtobufWireFormatException(
                    "Mismatched protobuf end-group field $innerFieldNumber (expected $endFieldNumber)",
                )
            }
            return
        }
        skipField(innerWireType, innerFieldNumber, depth)
    }
}

fun normalizeTimestamp(ts: Long): Long {
    if (ts <= 0L) return 0L
    // timestamps before 2000-01-01 in milliseconds are likely in seconds
    return if (ts < 946684800000L) ts * 1000L else ts
}

fun resolveFavoriteTimestamp(
    favoriteModifiedAt: Long?,
    dateAdded: Long,
    lastModifiedAt: Long,
): Long? {
    val candidates = listOfNotNull(
        dateAdded.takeIf { it > 0L },
        favoriteModifiedAt?.takeIf { it > 0L },
        lastModifiedAt.takeIf { it > 0L },
    )
    return candidates.firstOrNull()?.let { normalizeTimestamp(it) }
}

fun calculateProgressPercent(
    totalCount: Int,
    completedCount: Int,
): Float? {
    if (totalCount <= 0) return null
    val safeCompletedCount = completedCount.coerceIn(0, totalCount)
    return max(0f, safeCompletedCount.toFloat() / totalCount.toFloat())
}

private fun AniyomiBackupAnime.toRecord(
    app: ExternalBackupApp,
    sourceDisplayName: String? = null,
): ExternalBackupContentRecord? {
    val favoriteTimestamp = resolveFavoriteTimestamp(favoriteModifiedAt, dateAdded, lastModifiedAt)
    val history = history.maxByOrNull { it.lastRead }
    val progressPercent = calculateProgressPercent(
        totalCount = episodes.size,
        completedCount = episodes.count { it.seen },
    )
    if (!favorite && history == null) {
        return null
    }
    return ExternalBackupContentRecord(
        app = app,
        sourceName = "ANIYOMI_$source",
        sourceDisplayName = sourceDisplayName,
        contentType = ContentType.VIDEO,
        url = url,
        title = title,
        authors = listOfNotNull(author, artist)
            .distinct()
            .joinToString(", ")
            .ifBlank { null },
        description = description,
        tags = genre,
        coverUrl = thumbnailUrl,
        publicUrl = url,
        state = status.toString(),
        isFavorite = favorite,
        favoriteTimestamp = favoriteTimestamp,
        favoriteCategoryOrders = categories,
        chaptersCount = episodes.size,
        readEntriesCount = episodes.count { it.seen },
        progressPercent = progressPercent,
        historyChapterUrl = history?.url,
        historyTimestamp = history?.lastRead?.takeIf { it > 0L },
    )
}

private fun MihonBackupManga.toRecord(
    app: ExternalBackupApp,
    sourceName: String,
    contentType: ContentType,
    totalCount: Int,
    completedCount: Int,
    sourceDisplayName: String? = null,
): ExternalBackupContentRecord? {
    val favoriteTimestamp = resolveFavoriteTimestamp(favoriteModifiedAt, dateAdded, lastModifiedAt)
    val history = history.maxByOrNull { it.lastRead }
    val progressPercent = calculateProgressPercent(
        totalCount = totalCount,
        completedCount = completedCount,
    )
    if (!favorite && history == null) {
        return null
    }
    val progress = resolveMangaHistory(history, chapters, favoriteTimestamp)
    return ExternalBackupContentRecord(
        app = app,
        sourceName = sourceName,
        sourceDisplayName = sourceDisplayName,
        contentType = contentType,
        url = url,
        title = title,
        authors = listOfNotNull(author, artist)
            .distinct()
            .joinToString(", ")
            .ifBlank { null },
        description = description,
        tags = genre,
        coverUrl = thumbnailUrl,
        publicUrl = url,
        state = status.toString(),
        isFavorite = favorite,
        favoriteTimestamp = favoriteTimestamp,
        favoriteCategoryOrders = categories,
        chaptersCount = totalCount,
        readEntriesCount = completedCount,
        progressPercent = progressPercent,
        historyChapterUrl = progress?.chapterUrl,
        historyTimestamp = progress?.timestamp,
        historyPage = progress?.page ?: 0,
    )
}

private fun AniyomiBackupManga.toRecord(
    app: ExternalBackupApp,
    sourceName: String,
    contentType: ContentType,
    totalCount: Int,
    completedCount: Int,
    sourceDisplayName: String? = null,
): ExternalBackupContentRecord? {
    val favoriteTimestamp = resolveFavoriteTimestamp(favoriteModifiedAt, dateAdded, lastModifiedAt)
    val history = history.maxByOrNull { it.lastRead }
    val progressPercent = calculateProgressPercent(
        totalCount = totalCount,
        completedCount = completedCount,
    )
    if (!favorite && history == null) {
        return null
    }
    val progress = resolveMangaHistory(history, chapters, favoriteTimestamp)
    return ExternalBackupContentRecord(
        app = app,
        sourceName = sourceName,
        sourceDisplayName = sourceDisplayName,
        contentType = contentType,
        url = url,
        title = title,
        authors = listOfNotNull(author, artist)
            .distinct()
            .joinToString(", ")
            .ifBlank { null },
        description = description,
        tags = genre,
        coverUrl = thumbnailUrl,
        publicUrl = url,
        state = status.toString(),
        isFavorite = favorite,
        favoriteTimestamp = favoriteTimestamp,
        favoriteCategoryOrders = categories,
        chaptersCount = totalCount,
        readEntriesCount = completedCount,
        progressPercent = progressPercent,
        historyChapterUrl = progress?.chapterUrl,
        historyTimestamp = progress?.timestamp,
        historyPage = progress?.page ?: 0,
    )
}

/**
 * The chapter URL and timestamp to restore as reading history. Mihon's history points at the chapter opened last,
 * while chapters can be marked read without being opened (by hand, on another device, by a tracker), and Kototoro
 * keeps one stopping point that counts everything before it as read. So a dated history entry wins only while its
 * chapter is still unread, a resume point with a saved page; once that chapter is read (or missing from the
 * backup), a later chapter marked read moves the stopping point there, keeping the real last-read time. Titles
 * with no history at all fall back to the furthest read chapter, dated when the title was added to the library so
 * they sort below real history.
 */
private class MangaHistory(val chapterUrl: String, val timestamp: Long, val page: Int)

private fun resolveMangaHistory(
    history: MihonBackupHistory?,
    chapters: List<MihonBackupChapter>,
    favoriteTimestamp: Long?,
): MangaHistory? {
    val furthest = chapters.furthestReadChapter()
    if (history != null && history.lastRead > 0L) {
        val opened = chapters.firstOrNull { it.url == history.url }
        if (furthest != null && (opened == null || opened.read && furthest.isAfter(opened))) {
            return MangaHistory(furthest.url, history.lastRead, furthest.resumePage())
        }
        return MangaHistory(history.url, history.lastRead, opened?.resumePage() ?: 0)
    }
    val chapter = furthest ?: return null
    return MangaHistory(chapter.url, favoriteTimestamp ?: 1L, chapter.resumePage())
}

// Same zero-based index as the reader's page list; an out-of-range page opens the chapter from its start.
private fun MihonBackupChapter.resumePage(): Int = lastPageRead.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

// Chapter numbers decide when both chapters have one; otherwise Mihon's source order, which counts from the newest.
private fun MihonBackupChapter.isAfter(other: MihonBackupChapter): Boolean =
    if (chapterNumber > 0f && other.chapterNumber > 0f && chapterNumber != other.chapterNumber) {
        chapterNumber > other.chapterNumber
    } else {
        sourceOrder < other.sourceOrder
    }

private fun List<MihonBackupChapter>.furthestReadChapter(): MihonBackupChapter? {
    val read = filter { it.read }
    val numbered = read.filter { it.chapterNumber > 0f }
    return if (numbered.isNotEmpty()) {
        // Mihon's source order counts from the newest chapter, so ties go to the lower order.
        numbered.maxWithOrNull(compareBy<MihonBackupChapter> { it.chapterNumber }.thenByDescending { it.sourceOrder })
    } else {
        read.minByOrNull { it.sourceOrder }
    }
}

private fun List<MihonBackupCategory>.toFavoriteCategoryRecords(): List<ExternalBackupFavoriteCategoryRecord> {
    return filter { it.name.isNotBlank() }
        .distinctBy { it.name }
        .sortedBy { it.order }
        .map { ExternalBackupFavoriteCategoryRecord(name = it.name, order = it.order, id = it.id, flags = it.flags) }
}

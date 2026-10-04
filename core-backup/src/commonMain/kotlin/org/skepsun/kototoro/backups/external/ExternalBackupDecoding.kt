package org.skepsun.kototoro.backups.external

import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.buffer
import okio.gzip
import org.skepsun.kototoro.parsers.model.ContentType
import kotlin.math.max

private const val MAGIC_GZIP = 0x1f8b
private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d
private const val MAGIC_JSON_SIGNATURE2 = 0x7b22
private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a

/**
 * Reads the raw bytes of an external backup, transparently un-gzipping it.
 * JSON backups are rejected with [UnsupportedExternalBackupException].
 * Takes ownership of [source] and closes it on success or failure.
 */
fun readExternalBackupBytes(source: BufferedSource): ByteArray {
    var decoded = source
    try {
        val peeked = source.peek().apply { require(2) }
        val magic = peeked.readShort().toInt()
        decoded = when (magic) {
            MAGIC_GZIP -> source.gzip().buffer()
            MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                throw UnsupportedExternalBackupException("JSON backups are not supported")
            }
            else -> source
        }
        return decoded.readByteArray()
    } finally {
        decoded.close()
    }
}

/**
 * Decodes bytes using the selected Mihon or Aniyomi protobuf schema. Returns null when decoding fails.
 * Venera backups are SQLite based and are decoded by the Android-only decoder.
 */
fun decodeMihonOrAniyomiBackup(bytes: ByteArray, app: ExternalBackupApp): ExternalBackupPayload? = when (app) {
    ExternalBackupApp.MIHON -> runCatching {
        ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), bytes).toPayload(app)
    }.getOrNull()
    ExternalBackupApp.ANIYOMI -> runCatching {
        ProtoBuf.decodeFromByteArray(AniyomiBackup.serializer(), bytes).toPayload(app)
    }.getOrNull()
    ExternalBackupApp.VENERA -> null
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

private fun MihonBackup.toPayload(app: ExternalBackupApp): ExternalBackupPayload {
    val sourceNamesById = buildSourceNamesById(backupSources)
    return ExternalBackupPayload(
        records = backupManga.mapNotNull { manga ->
            manga.toRecord(
                app = app,
                sourceName = "MIHON_${manga.source}",
                sourceDisplayName = sourceNamesById[manga.source],
                contentType = ContentType.MANGA,
                totalCount = manga.chapters.size,
                completedCount = manga.chapters.count { it.read },
            )
        },
        favoriteCategories = backupCategories.toFavoriteCategoryRecords(),
    )
}

private fun AniyomiBackup.toPayload(app: ExternalBackupApp): ExternalBackupPayload {
    val sourceNamesById = buildSourceNamesById(backupSources + backupAnimeSources)
    val mangaRecords = backupManga.mapNotNull { manga ->
        manga.toRecord(
            app = app,
            sourceName = "MIHON_${manga.source}",
            sourceDisplayName = sourceNamesById[manga.source],
            contentType = ContentType.MANGA,
            totalCount = manga.chapters.size,
            completedCount = manga.chapters.count { it.read },
        )
    }
    val animeRecords = backupAnime.mapNotNull { anime ->
        val favoriteTimestamp = resolveFavoriteTimestamp(anime.favoriteModifiedAt, anime.dateAdded, anime.lastModifiedAt)
        val history = anime.history.maxByOrNull { it.lastRead }
        val progressPercent = calculateProgressPercent(
            totalCount = anime.episodes.size,
            completedCount = anime.episodes.count { it.seen },
        )
        if (!anime.favorite && history == null) {
            null
        } else {
            ExternalBackupContentRecord(
                app = app,
                sourceName = "ANIYOMI_${anime.source}",
                sourceDisplayName = sourceNamesById[anime.source],
                contentType = ContentType.VIDEO,
                url = anime.url,
                title = anime.title,
                authors = listOfNotNull(anime.author, anime.artist)
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { null },
                description = anime.description,
                tags = anime.genre,
                coverUrl = anime.thumbnailUrl,
                publicUrl = anime.url,
                state = anime.status.toString(),
                isFavorite = anime.favorite,
                favoriteTimestamp = favoriteTimestamp,
                favoriteCategoryOrders = anime.categories,
                chaptersCount = anime.episodes.size,
                readEntriesCount = anime.episodes.count { it.seen },
                progressPercent = progressPercent,
                historyChapterUrl = history?.url,
                historyTimestamp = history?.lastRead?.takeIf { it > 0L },
            )
        }
    }
    return ExternalBackupPayload(
        records = mangaRecords + animeRecords,
        favoriteCategories = backupCategories.toFavoriteCategoryRecords(),
    )
}

private fun buildSourceNamesById(
    sources: List<MihonBackupSource>,
): Map<Long, String> {
    return sources
        .filter { it.sourceId != 0L && it.name.isNotBlank() }
        .associate { it.sourceId to it.name }
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
        historyChapterUrl = history?.url,
        historyTimestamp = history?.lastRead?.takeIf { it > 0L },
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
        historyChapterUrl = history?.url,
        historyTimestamp = history?.lastRead?.takeIf { it > 0L },
    )
}

private fun List<MihonBackupCategory>.toFavoriteCategoryRecords(): List<ExternalBackupFavoriteCategoryRecord> {
    return filter { it.name.isNotBlank() }
        .distinctBy { it.name }
        .sortedBy { it.order }
        .map { ExternalBackupFavoriteCategoryRecord(name = it.name, order = it.order, id = it.id, flags = it.flags) }
}

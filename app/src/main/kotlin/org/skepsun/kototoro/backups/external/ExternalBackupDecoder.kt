package org.skepsun.kototoro.backups.external

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class ExternalBackupDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val parser = ProtoBuf

    fun decode(uri: Uri, app: ExternalBackupApp): ExternalBackupPayload {
        val bytes = readBackupBytes(uri)
        if (app == ExternalBackupApp.VENERA) {
            return decodeVeneraBackup(bytes)
        }
        return when (app.family) {
            ExternalBackupFamily.MANGA -> tryDecodeMangaBackup(bytes, app)
            ExternalBackupFamily.ANIME -> tryDecodeAnimeBackup(bytes, app)
        } ?: throw UnsupportedExternalBackupException("Unsupported external backup format")
    }

    private fun readBackupBytes(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val source = inputStream.source().buffer()
            val peeked = source.peek().apply { require(2) }
            val magic = peeked.readShort().toInt()
            val decoded = when (magic) {
                0x1f8b -> source.gzip().buffer()
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                    throw UnsupportedExternalBackupException("JSON backups are not supported")
                }
                else -> source
            }
            decoded.use { it.readByteArray() }
        } ?: throw IOException("Unable to open backup file")
    }

    private fun tryDecodeMangaBackup(
        bytes: ByteArray,
        app: ExternalBackupApp,
    ): ExternalBackupPayload? {
        return runCatching {
            parser.decodeFromByteArray(MihonBackup.serializer(), bytes).toPayload(app)
        }.getOrNull()
    }

    private fun tryDecodeAnimeBackup(
        bytes: ByteArray,
        app: ExternalBackupApp,
    ): ExternalBackupPayload? {
        return runCatching {
            parser.decodeFromByteArray(AniyomiBackup.serializer(), bytes)
        }.getOrNull()?.toPayload(app)
    }

    private fun decodeVeneraBackup(bytes: ByteArray): ExternalBackupPayload {
        val backupFile = File.createTempFile("venera_backup", ".venera", context.cacheDir)
        val databaseFiles = LinkedHashMap<String, File>()
        return try {
            backupFile.writeBytes(bytes)
            databaseFiles += extractVeneraDatabases(backupFile)
            val favoriteRecords = databaseFiles[FILENAME_VENERA_FAVORITES]?.let(::readVeneraFavorites).orEmpty()
            val historyRecords = databaseFiles[FILENAME_VENERA_HISTORY]?.let(::readVeneraHistory).orEmpty()
            ExternalBackupPayload(
                records = mergeVeneraRecords(favoriteRecords, historyRecords),
            )
        } finally {
            backupFile.delete()
            databaseFiles.values.forEach { it.delete() }
        }
    }

    private fun extractVeneraDatabases(file: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in VENERA_DATABASE_FILES) {
                    val file = File.createTempFile("venera_$name", ".db", context.cacheDir)
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    result[name] = file
                }
            }
        }
        return result
    }

    private fun readVeneraFavorites(file: File): List<ExternalBackupContentRecord> {
        val records = ArrayList<ExternalBackupContentRecord>()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val folderSourceKeys = db.queryVeneraFolderSourceKeys()
            val tables = db.queryUserTables()
                .filterNot { it in VENERA_FAVORITE_METADATA_TABLES || it.startsWith("sqlite_") }
            for (table in tables) {
                db.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.toVeneraFavoriteRecord(table, folderSourceKeys[table])?.let(records::add)
                    }
                }
            }
        }
        return records
    }

    private fun readVeneraHistory(file: File): List<ExternalBackupContentRecord> {
        val records = ArrayList<ExternalBackupContentRecord>()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (!db.hasTable("history")) {
                return emptyList()
            }
            db.rawQuery("SELECT * FROM history", null).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.toVeneraHistoryRecord()?.let(records::add)
                }
            }
        }
        return records
    }

    private fun mergeVeneraRecords(
        favorites: List<ExternalBackupContentRecord>,
        histories: List<ExternalBackupContentRecord>,
    ): List<ExternalBackupContentRecord> {
        val merged = LinkedHashMap<String, ExternalBackupContentRecord>()
        (favorites + histories).forEach { record ->
            val key = "${record.url}|${record.sourceCandidates.joinToString("|")}"
            val previous = merged[key]
            merged[key] = if (previous == null) {
                record
            } else {
                previous.copy(
                    isFavorite = previous.isFavorite || record.isFavorite,
                    favoriteTimestamp = previous.favoriteTimestamp ?: record.favoriteTimestamp,
                    historyChapterUrl = previous.historyChapterUrl ?: record.historyChapterUrl,
                    historyTimestamp = listOfNotNull(previous.historyTimestamp, record.historyTimestamp).maxOrNull(),
                    chaptersCount = max(previous.chaptersCount, record.chaptersCount),
                    readEntriesCount = max(previous.readEntriesCount, record.readEntriesCount),
                    progressPercent = previous.progressPercent ?: record.progressPercent,
                    sourceCandidates = (previous.sourceCandidates + record.sourceCandidates).distinct(),
                )
            }
        }
        return merged.values.toList()
    }

    private fun SQLiteDatabase.queryUserTables(): List<String> {
        return rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
    }

    private fun SQLiteDatabase.hasTable(table: String): Boolean {
        return rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun SQLiteDatabase.queryVeneraFolderSourceKeys(): Map<String, String> {
        if (!hasTable("folder_sync")) {
            return emptyMap()
        }
        return rawQuery("SELECT folder_name, source_key FROM folder_sync", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val folderName = cursor.getString(0).orEmpty()
                    val sourceKey = cursor.getString(1).orEmpty()
                    if (folderName.isNotBlank() && sourceKey.isNotBlank()) {
                        put(folderName, sourceKey)
                    }
                }
            }
        }
    }

    private fun Cursor.toVeneraFavoriteRecord(
        tableName: String,
        folderSourceKey: String?,
    ): ExternalBackupContentRecord? {
        val title = stringValue("name", "title").ifBlank { return null }
        val url = stringValue("id", "url", "comic_id").ifBlank { title }
        val sourceCandidates = veneraSourceCandidates(folderSourceKey, tableName)
        return ExternalBackupContentRecord(
            app = ExternalBackupApp.VENERA,
            sourceName = sourceCandidates.firstOrNull().orEmpty(),
            contentType = ContentType.MANGA,
            url = url,
            title = title,
            authors = stringValue("author", "subtitle").ifBlank { null },
            description = stringValue("description", "intro").ifBlank { null },
            tags = stringValue("tags").split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            coverUrl = stringValue("cover_path", "cover", "cover_url", "image").ifBlank { null },
            publicUrl = url,
            state = null,
            isFavorite = true,
            favoriteTimestamp = longValue("time", "created_at", "updated_at").takeIf { it > 0L }?.let(::normalizeTimestamp),
            favoriteCategoryOrders = emptyList(),
            chaptersCount = intValue("max_page", "episode_count", "chapter_count").coerceAtLeast(0),
            readEntriesCount = 0,
            progressPercent = null,
            historyChapterUrl = null,
            historyTimestamp = null,
            sourceCandidates = sourceCandidates,
        )
    }

    private fun Cursor.toVeneraHistoryRecord(): ExternalBackupContentRecord? {
        val title = stringValue("title", "name").ifBlank { return null }
        val url = stringValue("id", "url", "comic_id").ifBlank { title }
        val sourceCandidates = veneraSourceCandidates()
        val chapterIndex = intValue("ep", "episode", "chapter", "page")
        val maxPage = intValue("max_page", "maxPage", "chapters")
        return ExternalBackupContentRecord(
            app = ExternalBackupApp.VENERA,
            sourceName = sourceCandidates.firstOrNull().orEmpty(),
            contentType = ContentType.MANGA,
            url = url,
            title = title,
            authors = stringValue("subtitle", "author").ifBlank { null },
            description = null,
            tags = emptyList(),
            coverUrl = stringValue("cover", "cover_url", "image").ifBlank { null },
            publicUrl = url,
            state = null,
            isFavorite = false,
            favoriteTimestamp = null,
            favoriteCategoryOrders = emptyList(),
            chaptersCount = maxPage.coerceAtLeast(0),
            readEntriesCount = chapterIndex.coerceAtLeast(0),
            progressPercent = calculateProgressPercent(maxPage, chapterIndex),
            historyChapterUrl = buildString {
                append("venera:")
                append(url)
                append(":")
                append(stringValue("chapter_group", "group"))
                append(":")
                append(chapterIndex)
            },
            historyTimestamp = longValue("time", "updated_at", "last_read").takeIf { it > 0L }?.let(::normalizeTimestamp),
            sourceCandidates = sourceCandidates,
        )
    }

    private fun Cursor.veneraSourceCandidates(vararg extraCandidates: String?): List<String> {
        val type = intValue("type").takeIf { hasColumn("type") }
        return listOfNotNull(
            stringValue("source_key", "sourceKey", "source").takeIf { it.isNotBlank() },
            stringValue("source_name", "sourceName").takeIf { it.isNotBlank() },
            type?.let(::veneraLegacySourceKey),
            type?.toString(),
            *extraCandidates,
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun Cursor.stringValue(vararg names: String): String {
        for (name in names) {
            val index = getColumnIndex(name)
            if (index >= 0 && !isNull(index)) {
                return getString(index).orEmpty()
            }
        }
        return ""
    }

    private fun Cursor.hasColumn(name: String): Boolean {
        return getColumnIndex(name) >= 0
    }

    private fun Cursor.longValue(vararg names: String): Long {
        for (name in names) {
            val index = getColumnIndex(name)
            if (index >= 0 && !isNull(index)) {
                return runCatching { getLong(index) }.getOrElse { getString(index).toLongOrNull() ?: 0L }
            }
        }
        return 0L
    }

    private fun Cursor.intValue(vararg names: String): Int {
        return longValue(*names).toInt()
    }

    private fun normalizeTimestamp(ts: Long): Long {
        if (ts <= 0L) return 0L
        // timestamps before 2000-01-01 in milliseconds are likely in seconds
        return if (ts < 946684800000L) ts * 1000L else ts
    }

    private fun resolveFavoriteTimestamp(
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

    private fun calculateProgressPercent(
        totalCount: Int,
        completedCount: Int,
    ): Float? {
        if (totalCount <= 0) return null
        val safeCompletedCount = completedCount.coerceIn(0, totalCount)
        return max(0f, safeCompletedCount.toFloat() / totalCount.toFloat())
    }

    private companion object {
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a
        private const val FILENAME_VENERA_FAVORITES = "local_favorite.db"
        private const val FILENAME_VENERA_HISTORY = "history.db"
        private val VENERA_DATABASE_FILES = setOf(FILENAME_VENERA_FAVORITES, FILENAME_VENERA_HISTORY)
        private val VENERA_FAVORITE_METADATA_TABLES = setOf("folder_order", "folder_sync", "sqlite_sequence")

        private fun veneraLegacySourceKey(type: Int): String? {
            return when (type) {
                0 -> "picacg"
                1 -> "ehentai"
                2 -> "jm"
                3 -> "hitomi"
                4 -> "wnacg"
                5, 6 -> "nhentai"
                233488852 -> "baozi"
                29663848 -> "hot_manga"
                42816288 -> "manwaba"
                11995058 -> "lanraragi"
                150465061 -> "zaimanhua"
                236897507 -> "hcomic"
                258019538 -> "hitomi"
                264196719 -> "nhentai"
                331263271 -> "shonen_jump_plus"
                385625716 -> "ehentai"
                553570794 -> "picacg"
                550146035 -> "goda"
                557997769 -> "copy_manga"
                577341847 -> "mh1234"
                577718694 -> "manga_dex"
                631413104 -> "manhuaren"
                635587041 -> "komga"
                637999886 -> "Komiic"
                716010982 -> "ikmmh"
                740690276 -> "jcomic"
                771282371 -> "mxs"
                778108598 -> "mh18"
                798816513 -> "ykmh"
                807338462 -> "ccc"
                823512256 -> "wnacg"
                875043938 -> "kavita"
                893043064 -> "comic_walker"
                964788560 -> "comick"
                977805693 -> "happy"
                981441865 -> "ManHuaGui"
                769844263 -> "jm"
                else -> null
            }
        }
    }

    private fun MihonBackup.toPayload(app: ExternalBackupApp): ExternalBackupPayload {
        return ExternalBackupPayload(
            records = backupManga.mapNotNull { manga ->
                manga.toRecord(
                    app = app,
                    sourceName = "MIHON_${manga.source}",
                    contentType = ContentType.MANGA,
                    totalCount = manga.chapters.size,
                    completedCount = manga.chapters.count { it.read },
                )
            },
            favoriteCategories = backupCategories.toFavoriteCategoryRecords(),
        )
    }

    private fun AniyomiBackup.toPayload(app: ExternalBackupApp): ExternalBackupPayload {
        val mangaRecords = backupManga.mapNotNull { manga ->
            manga.toRecord(
                app = app,
                sourceName = "MIHON_${manga.source}",
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

    private fun MihonBackupManga.toRecord(
        app: ExternalBackupApp,
        sourceName: String,
        contentType: ContentType,
        totalCount: Int,
        completedCount: Int,
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
        android.util.Log.d("KototoroBackup", "AniyomiManga ts: favModAt=$favoriteModifiedAt dateAdd=$dateAdded lastMod=$lastModifiedAt resolved=$favoriteTimestamp title=$title")
        return ExternalBackupContentRecord(
            app = app,
            sourceName = sourceName,
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
}

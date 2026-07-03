package org.skepsun.kototoro.tracking.mangabaka.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream

private const val API_BASE_URL = "https://api.mangabaka.org/"
private const val DATABASE_ASSET_PATH = "v1/database/series.sqlite.tar.gz"

@Singleton
class MangaBakaMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient private val okHttpClient: OkHttpClient,
) {

    data class Status(
        val version: String?,
        val latestVersion: String?,
        val hasUpdate: Boolean,
        val sizeBytes: Long,
        val entryCount: Int,
        val isInstalled: Boolean,
        val downloadedAt: Long,
        val hasSearchIndex: Boolean,
        val searchIndexVersion: String?,
        val searchIndexEntries: Int,
    )

    data class RemoteDatabaseInfo(
        val version: String,
        val sizeBytes: Long,
        val downloadUrl: String,
        val etag: String?,
        val lastModified: String?,
    )

    data class SearchIndexState(
        val isUsable: Boolean,
        val datasetVersion: String?,
        val indexVersion: String?,
        val indexExists: Boolean,
    )

    data class SeriesCandidate(
        val title: String,
        val aliases: List<String>,
        val mappings: List<AnimeOfflineRepository.Mapping>,
    )

    @Serializable
    private data class Meta(
        val version: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        val downloadedAt: Long = 0L,
        val sizeBytes: Long = 0L,
        val searchIndexVersion: String? = null,
        val searchIndexBuiltAt: Long = 0L,
        val searchIndexEntries: Int = 0,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val rootDir: File
        get() = File(context.filesDir, "tracking/mangabaka").apply { mkdirs() }

    private val databaseFile: File
        get() = File(rootDir, "series.sqlite.tar.gz")

    private val extractedDatabaseFile: File
        get() = File(rootDir, "series.sqlite")

    private val indexedDatabaseFile: File
        get() = File(rootDir, "series.lookup.sqlite")

    private val metaFile: File
        get() = File(rootDir, "meta.json")

    suspend fun readStatus(): Status = withContext(Dispatchers.IO) {
        val meta = readMeta()
        val remote = fetchLatestDatabaseInfo()
        val installed = databaseFile.exists()
        val installedBytes = when {
            extractedDatabaseFile.exists() -> extractedDatabaseFile.length()
            installed -> databaseFile.length()
            else -> 0L
        }
        val version = meta.version
        val hasSearchIndex = hasUsableSearchIndex(meta)
        val latestVersion = remote?.version
        val hasUpdate = when {
            !installed -> latestVersion != null
            latestVersion.isNullOrBlank() -> false
            version.isNullOrBlank() -> true
            else -> latestVersion != version
        }
        Status(
            version = version,
            latestVersion = latestVersion,
            hasUpdate = hasUpdate,
            sizeBytes = remote?.sizeBytes ?: installedBytes,
            entryCount = readLocalEntryCount(),
            isInstalled = installed,
            downloadedAt = meta.downloadedAt,
            hasSearchIndex = hasSearchIndex,
            searchIndexVersion = meta.searchIndexVersion,
            searchIndexEntries = meta.searchIndexEntries,
        )
    }

    suspend fun fetchLatestDatabaseInfo(): RemoteDatabaseInfo? = withContext(Dispatchers.IO) {
        val url = API_BASE_URL.toHttpUrl()
            .newBuilder()
            .addEncodedPathSegments(DATABASE_ASSET_PATH)
            .build()
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        val response = okHttpClient.newCall(request).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext null
            }
            val lastModified = resp.header("Last-Modified")
            val version = lastModified ?: resp.header("ETag")?.trim('"') ?: return@withContext null
            RemoteDatabaseInfo(
                version = version,
                sizeBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L,
                downloadUrl = url.toString(),
                etag = resp.header("ETag"),
                lastModified = lastModified,
            )
        }
    }

    suspend fun downloadAndInstall(
        remote: RemoteDatabaseInfo,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempFile = File(rootDir, "series.sqlite.tar.gz.download")
        val request = Request.Builder()
            .url(remote.downloadUrl)
            .header("Accept", "application/octet-stream")
            .build()
        val response = okHttpClient.newCall(request).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("MangaBaka download failed: HTTP ${resp.code}")
            }
            val body = resp.body ?: error("MangaBaka response body is empty")
            val totalBytes = body.contentLength().coerceAtLeast(0L)
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }
        }
        tempFile.copyTo(databaseFile, overwrite = true)
        tempFile.delete()
        extractSQLiteArchive(databaseFile, extractedDatabaseFile)
        writeMeta(
            Meta(
                version = remote.version,
                etag = remote.etag,
                lastModified = remote.lastModified,
                downloadedAt = System.currentTimeMillis(),
                sizeBytes = extractedDatabaseFile.takeIf(File::exists)?.length() ?: databaseFile.length(),
            ),
        )
    }

    suspend fun deleteLocalDataset() = withContext(Dispatchers.IO) {
        listOf(
            File(rootDir, "series.sqlite.tar.gz.download"),
            File(rootDir, "series.sqlite.tmp"),
            File(rootDir, "series.lookup.sqlite.tmp"),
            databaseFile,
            extractedDatabaseFile,
            indexedDatabaseFile,
            metaFile,
        ).forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    suspend fun searchSeriesLocal(
        query: String,
        limit: Int = 5,
    ): List<SeriesCandidate> = withContext(Dispatchers.IO) {
        val normalizedQuery = normalizeTitle(query)
        val meta = readMeta()
        if (normalizedQuery.isBlank() || !hasUsableSearchIndex(meta)) {
            return@withContext emptyList()
        }
        val candidates = linkedMapOf<String, SeriesCandidate>()
        SQLiteDatabase.openDatabase(
            indexedDatabaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            val statements = listOf(
                """
                SELECT
                    series_id,
                    title,
                    native_title,
                    romanized_title,
                    titles,
                    source_anilist_id,
                    source_kitsu_id,
                    source_my_anime_list_id,
                    source_shikimori_id,
                    source_simkl_id
                FROM series_lookup
                WHERE
                    normalized_alias = ?
                LIMIT ?
                """.trimIndent() to arrayOf(
                    normalizedQuery,
                    limit.coerceIn(1, 10).toString(),
                ),
                """
                SELECT
                    series_id,
                    title,
                    native_title,
                    romanized_title,
                    titles,
                    source_anilist_id,
                    source_kitsu_id,
                    source_my_anime_list_id,
                    source_shikimori_id,
                    source_simkl_id
                FROM series_lookup
                WHERE
                    normalized_alias LIKE ? || '%'
                LIMIT ?
                """.trimIndent() to arrayOf(
                    normalizedQuery,
                    limit.coerceIn(1, 10).toString(),
                ),
            )
            for ((sql, args) in statements) {
                db.rawQuery(sql, args).use { cursor ->
                    while (cursor.moveToNext()) {
                        val seriesId = cursor.getStringOrNull("series_id")?.trim().orEmpty()
                        if (seriesId.isBlank()) continue
                        val candidate = buildSeriesCandidate(cursor) ?: continue
                        val key = seriesId
                        if (key.isBlank()) continue
                        candidates.putIfAbsent(key, candidate)
                    }
                }
                if (candidates.size >= limit.coerceIn(1, 10)) {
                    break
                }
            }
        }
        candidates.values.toList()
    }

    suspend fun readSearchIndexState(): SearchIndexState = withContext(Dispatchers.IO) {
        val meta = readMeta()
        SearchIndexState(
            isUsable = hasUsableSearchIndex(meta),
            datasetVersion = meta.version,
            indexVersion = meta.searchIndexVersion,
            indexExists = indexedDatabaseFile.exists(),
        )
    }

    suspend fun rebuildSearchIndex(
        onProgress: suspend (processedRows: Long, totalRows: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!extractedDatabaseFile.exists()) {
            error("MangaBaka SQLite snapshot is not installed")
        }
        val meta = readMeta()
        val tempIndexFile = File(rootDir, "series.lookup.sqlite.tmp")
        if (tempIndexFile.exists()) {
            tempIndexFile.delete()
        }
        val totalRows = SQLiteDatabase.openDatabase(
            extractedDatabaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sourceDb ->
            sourceDb.rawQuery("SELECT COUNT(*) FROM series", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }
        var indexedAliases = 0
        SQLiteDatabase.openOrCreateDatabase(tempIndexFile, null).use { targetDb ->
            targetDb.beginTransaction()
            try {
                targetDb.execSQL(
                    """
                    CREATE TABLE series_lookup (
                        series_id TEXT NOT NULL,
                        normalized_alias TEXT NOT NULL,
                        title TEXT,
                        native_title TEXT,
                        romanized_title TEXT,
                        titles TEXT,
                        source_anilist_id INTEGER,
                        source_kitsu_id INTEGER,
                        source_my_anime_list_id INTEGER,
                        source_shikimori_id INTEGER,
                        source_simkl_id INTEGER,
                        PRIMARY KEY(series_id, normalized_alias)
                    )
                    """.trimIndent(),
                )
                SQLiteDatabase.openDatabase(
                    extractedDatabaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sourceDb ->
                    val availableColumns = sourceDb.readTableColumns("series")
                    val insert = targetDb.compileStatement(
                        """
                        INSERT OR REPLACE INTO series_lookup (
                            series_id,
                            normalized_alias,
                            title,
                            native_title,
                            romanized_title,
                            titles,
                            source_anilist_id,
                            source_kitsu_id,
                            source_my_anime_list_id,
                            source_shikimori_id,
                            source_simkl_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    )
                    sourceDb.rawQuery(
                        buildSeriesProjectionSql(availableColumns),
                        emptyArray(),
                    ).use { cursor ->
                        var processedRows = 0L
                        while (cursor.moveToNext()) {
                            val seriesId = cursor.getStringOrNull("id")?.trim().orEmpty()
                            if (seriesId.isNotBlank()) {
                                val title = cursor.getStringOrNull("title")
                                val nativeTitle = cursor.getStringOrNull("native_title")
                                val romanizedTitle = cursor.getStringOrNull("romanized_title")
                                val titlesJson = cursor.getStringOrNull("titles")
                                val aliases = linkedSetOf<String>()
                                title?.trim()?.takeIf(String::isNotBlank)?.let(aliases::add)
                                nativeTitle?.trim()?.takeIf(String::isNotBlank)?.let(aliases::add)
                                romanizedTitle?.trim()?.takeIf(String::isNotBlank)?.let(aliases::add)
                                parseTitlesJson(titlesJson.orEmpty()).forEach(aliases::add)
                                aliases.map(::normalizeTitle)
                                    .filter(String::isNotBlank)
                                    .distinct()
                                    .forEach { normalizedAlias ->
                                        insert.clearBindings()
                                        insert.bindString(1, seriesId)
                                        insert.bindString(2, normalizedAlias)
                                        bindStringOrNull(insert, 3, title)
                                        bindStringOrNull(insert, 4, nativeTitle)
                                        bindStringOrNull(insert, 5, romanizedTitle)
                                        bindStringOrNull(insert, 6, titlesJson)
                                        bindLongOrNull(insert, 7, cursor.getLongOrNull("source_anilist_id"))
                                        bindLongOrNull(insert, 8, cursor.getLongOrNull("source_kitsu_id"))
                                        bindLongOrNull(insert, 9, cursor.getLongOrNull("source_my_anime_list_id"))
                                        bindLongOrNull(insert, 10, cursor.getLongOrNull("source_shikimori_id"))
                                        bindLongOrNull(insert, 11, cursor.getLongOrNull("source_simkl_id"))
                                        insert.executeInsert()
                                        indexedAliases += 1
                                    }
                            }
                            processedRows += 1
                            if (processedRows % 500L == 0L || processedRows == totalRows) {
                                onProgress(processedRows, totalRows)
                            }
                        }
                    }
                }
                targetDb.execSQL(
                    "CREATE INDEX index_series_lookup_normalized_alias ON series_lookup(normalized_alias)",
                )
                targetDb.setTransactionSuccessful()
            } finally {
                targetDb.endTransaction()
            }
        }
        tempIndexFile.copyTo(indexedDatabaseFile, overwrite = true)
        tempIndexFile.delete()
        writeMeta(
            meta.copy(
                searchIndexVersion = meta.version,
                searchIndexBuiltAt = System.currentTimeMillis(),
                searchIndexEntries = indexedAliases,
            ),
        )
    }

    private fun readLocalEntryCount(): Int {
        if (!extractedDatabaseFile.exists()) {
            return 0
        }
        return runCatching {
            SQLiteDatabase.openDatabase(
                extractedDatabaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM series", emptyArray()).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
    }

    suspend fun searchSeries(
        query: String,
        limit: Int = 5,
    ): List<SeriesCandidate> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return@withContext emptyList()
        }
        val requestUrl = API_BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("v1")
            .addPathSegment("series")
            .addPathSegment("search")
            .addQueryParameter("q", normalizedQuery)
            .addQueryParameter("limit", limit.coerceIn(1, 10).toString())
            .build()
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
            .build()
        val response = okHttpClient.newCall(request).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext emptyList()
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext emptyList()
            }
            parseSeriesCandidates(JSONObject(body))
        }
    }

    private fun parseSeriesCandidates(root: JSONObject): List<SeriesCandidate> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) {
                    continue
                }
                val aliases = linkedSetOf<String>()
                aliases += title
                item.optString("native_title").trim().takeIf(String::isNotBlank)?.let(aliases::add)
                item.optString("romanized_title").trim().takeIf(String::isNotBlank)?.let(aliases::add)
                val titles = item.optJSONArray("titles")
                if (titles != null) {
                    for (titleIndex in 0 until titles.length()) {
                        titles.optJSONObject(titleIndex)
                            ?.optString("title")
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                            ?.let(aliases::add)
                    }
                }
                val mappings = buildMappings(item.optJSONObject("source"))
                add(
                    SeriesCandidate(
                        title = title,
                        aliases = aliases.toList(),
                        mappings = mappings,
                    ),
                )
            }
        }
    }

    private fun buildSeriesCandidate(cursor: android.database.Cursor): SeriesCandidate? {
        val title = cursor.getStringOrNull("title")?.trim().orEmpty()
        if (title.isBlank()) {
            return null
        }
        val aliases = linkedSetOf<String>()
        aliases += title
        cursor.getStringOrNull("native_title")?.trim()?.takeIf(String::isNotBlank)?.let(aliases::add)
        cursor.getStringOrNull("romanized_title")?.trim()?.takeIf(String::isNotBlank)?.let(aliases::add)
        cursor.getStringOrNull("titles")
            ?.let(::parseTitlesJson)
            ?.forEach(aliases::add)
        val mappings = buildList {
            cursor.getLongOrNull("source_anilist_id")?.let { addMapping(ScrobblerService.ANILIST, it) }
            cursor.getLongOrNull("source_kitsu_id")?.let { addMapping(ScrobblerService.KITSU, it) }
            cursor.getLongOrNull("source_my_anime_list_id")?.let { addMapping(ScrobblerService.MAL, it) }
            cursor.getLongOrNull("source_shikimori_id")?.let { addMapping(ScrobblerService.SHIKIMORI, it) }
            cursor.getLongOrNull("source_simkl_id")?.let { addMapping(ScrobblerService.SIMKL, it) }
        }
        return SeriesCandidate(
            title = title,
            aliases = aliases.toList(),
            mappings = mappings,
        )
    }

    private fun MutableList<AnimeOfflineRepository.Mapping>.addMapping(
        service: ScrobblerService,
        remoteId: Long,
    ) {
        add(
            AnimeOfflineRepository.Mapping(
                service = service,
                remoteId = remoteId,
                title = null,
                url = null,
            ),
        )
    }

    private fun parseTitlesJson(raw: String): List<String> {
        return runCatching {
            val payload = org.json.JSONArray(raw)
            buildList {
                for (index in 0 until payload.length()) {
                    val item = payload.optJSONObject(index) ?: continue
                    item.optString("title")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractSQLiteArchive(
        archiveFile: File,
        outputFile: File,
    ) {
        val tempOutput = File(outputFile.parentFile, "${outputFile.name}.tmp")
        FileInputStream(archiveFile).use { fileInput ->
            GZIPInputStream(fileInput).use { gzipInput ->
                TarArchiveInputStream(gzipInput).use { tarInput ->
                    var entry = tarInput.nextTarEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".sqlite")) {
                            tempOutput.outputStream().use { output ->
                                tarInput.copyTo(output)
                                output.flush()
                            }
                            tempOutput.copyTo(outputFile, overwrite = true)
                            tempOutput.delete()
                            return
                        }
                        entry = tarInput.nextTarEntry
                    }
                }
            }
        }
        error("MangaBaka SQLite archive does not contain a .sqlite file")
    }

    private fun normalizeTitle(value: String): String {
        return value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff\\u3040-\\u30ff\\u31f0-\\u31ff\\uff66-\\uff9d]"), "")
    }

    private fun hasUsableSearchIndex(meta: Meta): Boolean {
        return indexedDatabaseFile.exists() &&
            meta.searchIndexVersion != null &&
            meta.searchIndexVersion == meta.version
    }

    private fun bindStringOrNull(
        statement: android.database.sqlite.SQLiteStatement,
        index: Int,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            statement.bindNull(index)
        } else {
            statement.bindString(index, value)
        }
    }

    private fun bindLongOrNull(
        statement: android.database.sqlite.SQLiteStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) {
            statement.bindNull(index)
        } else {
            statement.bindLong(index, value)
        }
    }

    private fun android.database.Cursor.getColumnIndexOrNull(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index >= 0) index else -1
    }

    private fun android.database.Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndexOrNull(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndexOrNull(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun SQLiteDatabase.readTableColumns(tableName: String): Set<String> {
        return rawQuery("PRAGMA table_info($tableName)", emptyArray()).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.getStringOrNull("name")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
    }

    private fun buildSeriesProjectionSql(availableColumns: Set<String>): String {
        val projection = listOf(
            "id",
            "title",
            "native_title",
            "romanized_title",
            "titles",
            "source_anilist_id",
            "source_kitsu_id",
            "source_my_anime_list_id",
            "source_shikimori_id",
            "source_simkl_id",
        ).joinToString(",\n") { column ->
            if (column in availableColumns) {
                column
            } else {
                "NULL AS $column"
            }
        }
        return """
            SELECT
                $projection
            FROM series
        """.trimIndent()
    }

    private fun buildMappings(source: JSONObject?): List<AnimeOfflineRepository.Mapping> {
        if (source == null) return emptyList()
        return buildList {
            appendMapping(source, "anilist", ScrobblerService.ANILIST)
            appendMapping(source, "kitsu", ScrobblerService.KITSU)
            appendMapping(source, "my_anime_list", ScrobblerService.MAL)
            appendMapping(source, "shikimori", ScrobblerService.SHIKIMORI)
            appendMapping(source, "simkl", ScrobblerService.SIMKL)
        }
    }

    private fun MutableList<AnimeOfflineRepository.Mapping>.appendMapping(
        source: JSONObject,
        key: String,
        service: ScrobblerService,
    ) {
        val payload = source.optJSONObject(key) ?: return
        val rawId = payload.opt("id") ?: return
        val remoteId = when (rawId) {
            is Number -> rawId.toLong()
            is String -> rawId.toLongOrNull()
            else -> null
        } ?: return
        add(
            AnimeOfflineRepository.Mapping(
                service = service,
                remoteId = remoteId,
                title = null,
                url = null,
            ),
        )
    }

    private fun readMeta(): Meta {
        if (!metaFile.exists()) {
            return Meta()
        }
        return runCatching {
            json.decodeFromString(Meta.serializer(), metaFile.readText())
        }.getOrDefault(Meta())
    }

    private fun writeMeta(meta: Meta) {
        metaFile.writeText(json.encodeToString(Meta.serializer(), meta))
    }
}

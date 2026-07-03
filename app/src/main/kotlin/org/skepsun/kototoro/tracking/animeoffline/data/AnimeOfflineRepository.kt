package org.skepsun.kototoro.tracking.animeoffline.data

import android.content.Context
import com.google.gson.stream.JsonReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.core.network.BaseHttpClient

private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/manami-project/anime-offline-database/releases/latest"
private val VIDEO_TYPES = setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)

@Singleton
class AnimeOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient private val okHttpClient: OkHttpClient,
) {

    data class Status(
        val isInstalled: Boolean,
        val releaseTag: String?,
        val assetName: String?,
        val downloadedAt: Long,
        val lastCheckedAt: Long,
        val installedBytes: Long,
        val entryCount: Int,
    )

    data class Mapping(
        val service: ScrobblerService,
        val remoteId: Long,
        val title: String?,
        val url: String?,
    )

    data class ReleaseInfo(
        val tag: String,
        val assetName: String,
        val downloadUrl: String,
    )

    data class CanonicalMatch(
        val canonicalId: String,
        val title: String,
        val synonyms: List<String>,
        val year: Int?,
        val type: String?,
        val mappings: List<Mapping>,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    @Volatile
    private var cachedState: CachedState? = null

    suspend fun shouldCheckForUpdates(now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        val meta = readMeta()
        now - meta.lastCheckedAt >= CHECK_INTERVAL_MS
    }

    suspend fun readStatus(): Status = withContext(Dispatchers.IO) {
        val meta = readMeta()
        val state = loadState()
        Status(
            isInstalled = indexFile.exists(),
            releaseTag = meta.releaseTag,
            assetName = meta.assetName,
            downloadedAt = meta.downloadedAt,
            lastCheckedAt = meta.lastCheckedAt,
            installedBytes = indexFile.takeIf { it.exists() }?.length() ?: 0L,
            entryCount = state.entries.size,
        )
    }

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GITHUB_LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val response = okHttpClient.newCall(request).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext null
            }
            parseLatestRelease(JSONObject(body))
        }
    }

    suspend fun isUpdateRequired(latest: ReleaseInfo): Boolean = withContext(Dispatchers.IO) {
        val meta = readMeta()
        meta.releaseTag != latest.tag || !indexFile.exists()
    }

    suspend fun recordCheck(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        writeMeta(readMeta().copy(lastCheckedAt = now))
    }

    suspend fun deleteLocalDataset() = withContext(Dispatchers.IO) {
        listOf(
            File(rootDir, "anime-offline-download.tmp"),
            File(rootDir, "anime-offline-index.tmp"),
            indexFile,
            metaFile,
        ).forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        mutex.withLock {
            cachedState = null
        }
    }

    suspend fun downloadAndInstall(
        release: ReleaseInfo,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        ensureRootDir()
        val tempDownload = File(rootDir, "anime-offline-download.tmp")
        val tempIndex = File(rootDir, "anime-offline-index.tmp")
        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("Accept", "application/octet-stream")
            .build()
        val response = okHttpClient.newCall(request).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("Anime offline download failed: HTTP ${resp.code}")
            }
            val body = resp.body ?: error("Anime offline response body is empty")
            val totalBytes = body.contentLength().coerceAtLeast(0L)
            body.byteStream().use { input ->
                tempDownload.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }
        }
        val compactIndex = tempDownload.inputStream().use(::parseCompactIndex)
        tempIndex.writeText(json.encodeToString(CompactIndex.serializer(), compactIndex))
        tempDownload.delete()
        tempIndex.copyTo(indexFile, overwrite = true)
        tempIndex.delete()
        writeMeta(
            readMeta().copy(
                releaseTag = release.tag,
                assetName = release.assetName,
                downloadedAt = System.currentTimeMillis(),
            ),
        )
        mutex.withLock {
            cachedState = null
        }
    }

    suspend fun resolveMappings(
        service: ScrobblerService,
        remoteId: Long,
    ): List<Mapping> = withContext(Dispatchers.Default) {
        val state = loadState()
        val entry = state.byServiceRemote["${service.id}:$remoteId"] ?: return@withContext emptyList()
        entry.mappings.mapNotNull { mapping ->
            val targetService = ScrobblerService.entries.firstOrNull { it.id == mapping.serviceId } ?: return@mapNotNull null
            Mapping(
                service = targetService,
                remoteId = mapping.remoteId,
                title = entry.title,
                url = mapping.url,
            )
        }
    }

    suspend fun matchLocalVideoContent(
        service: ScrobblerService,
        content: Content,
        limit: Int,
    ): List<TrackingSiteMatchResult> = withContext(Dispatchers.Default) {
        if (content.source.getContentType() !in VIDEO_TYPES) {
            return@withContext emptyList()
        }
        val state = loadState()
        if (state.entries.isEmpty()) {
            return@withContext emptyList()
        }
        val resultLimit = if (limit > 0) limit else 5
        val queries = buildCandidateQueries(content)
        val candidates = LinkedHashMap<String, TrackingSiteMatchResult>()
        for (query in queries) {
            val normalized = normalizeTitle(query)
            if (normalized.isBlank()) {
                continue
            }
            val entries = state.byNormalizedTitle[normalized].orEmpty()
            for (entry in entries) {
                val mapping = entry.mappings.firstOrNull { it.serviceId == service.id } ?: continue
                val confidence = when {
                    normalizeTitle(entry.title) == normalized -> 0.995f
                    entry.synonyms.any { normalizeTitle(it) == normalized } -> 0.985f
                    else -> 0.96f
                }
                val key = "${service.id}:${mapping.remoteId}"
                val current = candidates[key]
                if (current == null || confidence > current.confidence) {
	                    candidates[key] = TrackingSiteMatchResult(
	                        service = service,
	                        remoteId = mapping.remoteId,
	                        localContent = content,
	                        contentType = content.source.contentType,
	                        confidence = confidence,
	                        title = entry.title,
	                        url = mapping.url,
                        reason = if (normalizeTitle(entry.title) == normalized) "anime_offline_title" else "anime_offline_synonym",
                        isLinked = false,
                        isManual = false,
                    )
                }
            }
        }
        candidates.values
            .sortedWith(compareByDescending<TrackingSiteMatchResult> { it.confidence }.thenBy { it.title })
            .take(resultLimit)
    }

    suspend fun resolveCandidateTitlesForLocalVideoContent(
        content: Content,
        limit: Int = 8,
    ): List<String> = withContext(Dispatchers.Default) {
        if (content.source.getContentType() !in VIDEO_TYPES) {
            return@withContext emptyList()
        }
        val state = loadState()
        if (state.entries.isEmpty()) {
            return@withContext emptyList()
        }
        val matches = LinkedHashMap<String, CompactEntry>()
        buildCandidateQueries(content).forEach { query ->
            val normalized = normalizeTitle(query)
            if (normalized.isBlank()) {
                return@forEach
            }
            state.byNormalizedTitle[normalized].orEmpty().forEach { entry ->
                matches.putIfAbsent(entry.id, entry)
            }
        }
        matches.values
            .flatMap { entry -> listOf(entry.title) + entry.synonyms }
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeTitle)
            .take(limit)
    }

    suspend fun resolveCanonicalMatchForLocalVideoContent(
        content: Content,
    ): CanonicalMatch? = withContext(Dispatchers.Default) {
        if (content.source.getContentType() !in VIDEO_TYPES) {
            return@withContext null
        }
        val state = loadState()
        if (state.entries.isEmpty()) {
            return@withContext null
        }
        val matchedEntry = buildCandidateQueries(content)
            .asSequence()
            .map(::normalizeTitle)
            .filter(String::isNotBlank)
            .flatMap { normalized -> state.byNormalizedTitle[normalized].orEmpty().asSequence() }
            .distinctBy(CompactEntry::id)
            .maxByOrNull { entry ->
                maxOf(
                    similarity(normalizeTitle(content.title), normalizeTitle(entry.title)),
                    entry.synonyms.maxOfOrNull { similarity(normalizeTitle(content.title), normalizeTitle(it)) } ?: 0f,
                )
            }
            ?: return@withContext null
        CanonicalMatch(
            canonicalId = matchedEntry.id,
            title = matchedEntry.title,
            synonyms = matchedEntry.synonyms,
            year = matchedEntry.year,
            type = matchedEntry.type,
            mappings = matchedEntry.mappings.mapNotNull { mapping ->
                val service = ScrobblerService.entries.firstOrNull { it.id == mapping.serviceId } ?: return@mapNotNull null
                Mapping(
                    service = service,
                    remoteId = mapping.remoteId,
                    title = matchedEntry.title,
                    url = mapping.url,
                )
            },
        )
    }

    private suspend fun loadState(): CachedState {
        cachedState?.let { return it }
        return mutex.withLock {
            cachedState?.let { return@withLock it }
            val state = if (!indexFile.exists()) {
                CachedState(emptyList(), emptyMap(), emptyMap())
            } else {
                val compactIndex = json.decodeFromString(CompactIndex.serializer(), indexFile.readText())
                buildCachedState(compactIndex.entries)
            }
            cachedState = state
            state
        }
    }

    private fun buildCachedState(entries: List<CompactEntry>): CachedState {
        val byServiceRemote = HashMap<String, CompactEntry>(entries.sumOf { it.mappings.size }.coerceAtLeast(16))
        val byNormalizedTitle = HashMap<String, MutableList<CompactEntry>>()
        entries.forEach { entry ->
            entry.mappings.forEach { mapping ->
                byServiceRemote["${mapping.serviceId}:${mapping.remoteId}"] = entry
            }
            buildList {
                add(entry.title)
                addAll(entry.synonyms)
            }.map(::normalizeTitle)
                .filter(String::isNotBlank)
                .distinct()
                .forEach { normalized ->
                    byNormalizedTitle.getOrPut(normalized) { mutableListOf() }.add(entry)
                }
        }
        return CachedState(
            entries = entries,
            byServiceRemote = byServiceRemote,
            byNormalizedTitle = byNormalizedTitle,
        )
    }

    private fun parseLatestRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: return null
        val preferredNames = listOf(
            "anime-offline-database-minified.json",
            "anime-offline-database.json",
        )
        for (preferred in preferredNames) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == preferred) {
                    val url = asset.optString("browser_download_url").ifBlank { continue }
                    return ReleaseInfo(tag = tag, assetName = preferred, downloadUrl = url)
                }
            }
        }
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".json") || name.endsWith(".json.sha256")) {
                continue
            }
            val url = asset.optString("browser_download_url").ifBlank { continue }
            return ReleaseInfo(tag = tag, assetName = name, downloadUrl = url)
        }
        return null
    }

    private fun parseCompactIndex(inputFileStream: java.io.InputStream): CompactIndex {
        val entries = ArrayList<CompactEntry>()
        JsonReader(InputStreamReader(inputFileStream)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "data" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            parseEntry(reader)?.let(entries::add)
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return CompactIndex(entries = entries)
    }

    private fun parseEntry(reader: JsonReader): CompactEntry? {
        var title = ""
        var type: String? = null
        var year: Int? = null
        val synonyms = mutableListOf<String>()
        val mappings = mutableListOf<CompactMapping>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> title = nextStringOrEmpty(reader)
                "type" -> type = nextNullableString(reader)
                "synonyms" -> readStringArray(reader, synonyms)
                "sources" -> readMappings(reader, mappings)
                "animeSeason" -> year = readSeasonYear(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (title.isBlank() || mappings.isEmpty()) {
            return null
        }
        return CompactEntry(
            id = buildCanonicalId(mappings),
            title = title,
            synonyms = synonyms.filter { it.isNotBlank() }.distinct(),
            year = year,
            type = type,
            mappings = mappings.distinctBy { "${it.serviceId}:${it.remoteId}" },
        )
    }

    private fun readMappings(reader: JsonReader, output: MutableList<CompactMapping>) {
        reader.beginArray()
        while (reader.hasNext()) {
            val url = nextStringOrEmpty(reader)
            parseMapping(url)?.let(output::add)
        }
        reader.endArray()
    }

    private fun parseMapping(url: String): CompactMapping? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return when {
            MAL_REGEX.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.MAL.id, MAL_REGEX.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            ANILIST_REGEX.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.ANILIST.id, ANILIST_REGEX.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            KITSU_REGEX.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.KITSU.id, KITSU_REGEX.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            SHIKIMORI_REGEX.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.SHIKIMORI.id, SHIKIMORI_REGEX.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            BANGUMI_REGEX.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.BANGUMI.id, BANGUMI_REGEX.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            MANGA_UPDATES_REGEX_1.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.MANGAUPDATES.id, MANGA_UPDATES_REGEX_1.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            MANGA_UPDATES_REGEX_2.containsMatchIn(trimmed) -> CompactMapping(ScrobblerService.MANGAUPDATES.id, MANGA_UPDATES_REGEX_2.find(trimmed)!!.groupValues[1].toLong(), trimmed)
            else -> null
        }
    }

    private fun buildCanonicalId(mappings: List<CompactMapping>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val raw = mappings
            .sortedWith(compareBy<CompactMapping> { it.serviceId }.thenBy { it.remoteId })
            .joinToString("|") { "${it.serviceId}:${it.remoteId}" }
        return digest.digest(raw.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildCandidateQueries(content: Content): List<String> {
        return buildList {
            add(content.title.trim())
            addAll(content.altTitles.map(String::trim))
        }.filter { it.isNotBlank() }
            .distinctBy(::normalizeTitle)
    }

    private fun readStringArray(reader: JsonReader, output: MutableList<String>) {
        reader.beginArray()
        while (reader.hasNext()) {
            nextNullableString(reader)?.let(output::add)
        }
        reader.endArray()
    }

    private fun readSeasonYear(reader: JsonReader): Int? {
        var year: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "year" -> year = when (reader.peek()) {
                    com.google.gson.stream.JsonToken.NULL -> {
                        reader.nextNull()
                        null
                    }
                    com.google.gson.stream.JsonToken.NUMBER -> reader.nextInt()
                    else -> {
                        val text = nextNullableString(reader)
                        text?.toIntOrNull()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return year
    }

    private fun nextStringOrEmpty(reader: JsonReader): String = nextNullableString(reader).orEmpty()

    private fun nextNullableString(reader: JsonReader): String? {
        return when (reader.peek()) {
            com.google.gson.stream.JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> reader.nextString()
        }
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff\\u3040-\\u30ff\\u31f0-\\u31ff\\uff66-\\uff9d]"), "")
    }

    private fun similarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        val maxLength = maxOf(left.length, right.length).coerceAtLeast(1)
        val distance = levenshtein(left, right)
        return (1f - distance.toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[right.length]
    }

    private fun ensureRootDir() {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    private fun readMeta(): Meta {
        ensureRootDir()
        if (!metaFile.exists()) {
            return Meta()
        }
        return runCatching {
            json.decodeFromString(Meta.serializer(), metaFile.readText())
        }.getOrDefault(Meta())
    }

    private fun writeMeta(meta: Meta) {
        ensureRootDir()
        metaFile.writeText(json.encodeToString(Meta.serializer(), meta))
    }

    private val rootDir: File
        get() = File(context.filesDir, "anime_offline")

    private val metaFile: File
        get() = File(rootDir, "meta.json")

    private val indexFile: File
        get() = File(rootDir, "compact-index.json")

    @Serializable
    private data class Meta(
        val releaseTag: String? = null,
        val assetName: String? = null,
        val downloadedAt: Long = 0L,
        val lastCheckedAt: Long = 0L,
    )

    @Serializable
    private data class CompactIndex(
        val entries: List<CompactEntry>,
    )

    @Serializable
    private data class CompactEntry(
        val id: String,
        val title: String,
        val synonyms: List<String>,
        val year: Int? = null,
        val type: String? = null,
        val mappings: List<CompactMapping>,
    )

    @Serializable
    private data class CompactMapping(
        val serviceId: Int,
        val remoteId: Long,
        val url: String? = null,
    )

    private data class CachedState(
        val entries: List<CompactEntry>,
        val byServiceRemote: Map<String, CompactEntry>,
        val byNormalizedTitle: Map<String, List<CompactEntry>>,
    )

    private companion object {
        val MAL_REGEX = Regex("""myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE)
        val ANILIST_REGEX = Regex("""anilist\.co/anime/(\d+)""", RegexOption.IGNORE_CASE)
        val KITSU_REGEX = Regex("""kitsu\.(?:io|app)/anime/(\d+)""", RegexOption.IGNORE_CASE)
        val SHIKIMORI_REGEX = Regex("""shikimori\.(?:one|me)/animes/(\d+)""", RegexOption.IGNORE_CASE)
        val BANGUMI_REGEX = Regex("""(?:bgm\.tv|bangumi\.tv|chii\.in)/subject/(\d+)""", RegexOption.IGNORE_CASE)
        val MANGA_UPDATES_REGEX_1 = Regex("""mangaupdates\.com/series/(\d+)""", RegexOption.IGNORE_CASE)
        val MANGA_UPDATES_REGEX_2 = Regex("""mangaupdates\.com/series\.html\?id=(\d+)""", RegexOption.IGNORE_CASE)
    }
}

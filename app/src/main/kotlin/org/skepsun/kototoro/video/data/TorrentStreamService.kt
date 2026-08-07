package org.skepsun.kototoro.video.data

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import com.frostwire.jlibtorrent.FileStorage
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class TorrentStreamService @Inject constructor(
    @ApplicationContext context: Context,
    @ContentHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
) {
    private val cacheRoot = File(context.applicationContext.cacheDir, TORRENT_CACHE_DIRECTORY)
    private val runtimeMutex = Mutex()
    private val torrentMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var runtime: TorrentRuntime? = null

    fun peerStats(streamUrl: String): TorrentPeerStats? {
        val token = streamToken(streamUrl) ?: return null
        return runtime?.server?.peerStats(token)
    }

    fun pause(streamUrl: String?) {
        val token = streamUrl?.let(::streamToken) ?: return
        runtime?.server?.pause(token)
    }

    fun resume(streamUrl: String?) {
        val token = streamUrl?.let(::streamToken) ?: return
        runtime?.server?.resume(token)
    }

    fun release(streamUrl: String?) {
        val token = streamUrl?.let(::streamToken) ?: return
        val torrentRuntime = runtime ?: return
        val releasedTorrentId = torrentRuntime.server.unregister(token) ?: return
        scope.launch {
            torrentMutex.withLock {
                pruneCache(torrentRuntime, protectedIds = setOf(releasedTorrentId))
            }
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        torrentMutex.withLock {
            val activeIds = runtime?.server?.activeTorrentIds().orEmpty()
            runtime?.let { torrentRuntime ->
                torrentRuntime.manager.getTorrentHandles()
                    .filter { handle ->
                        handle.isValid && runCatching { torrentCacheKey(handle.torrentFile()) !in activeIds }.getOrDefault(false)
                    }
                    .forEach { handle ->
                        runCatching {
                            handle.clearPieceDeadlines()
                            handle.pause()
                            torrentRuntime.manager.remove(handle)
                            waitUntilRemoved(handle)
                        }
                    }
            }
            cacheRoot.listFiles().orEmpty().forEach { entry ->
                if (entry.name !in activeIds) {
                    check(entry.deleteRecursively() || !entry.exists()) { "Unable to clear torrent cache entry" }
                }
            }
        }
    }

    suspend fun resolve(locator: String, headers: Map<String, String> = emptyMap()): String =
        openStream(locator, headers).streamUrl

    suspend fun openStream(locator: String, headers: Map<String, String> = emptyMap()): TorrentResolvedStream =
        withContext(Dispatchers.IO) {
            val torrentRuntime = ensureRuntime()
            val metadata = loadMetadata(torrentRuntime.manager, locator, headers)
            TRACKERS.forEach { tracker -> runCatching { metadata.addTracker(tracker) } }

            val files = metadata.files()
            val availableIndices = (0 until files.numFiles()).filter { index ->
                !files.padFileAt(index) && files.fileSize(index) > 0
            }
            val selectedIndex = selectTorrentFileIndex(torrentFileIndex(locator), availableIndices)
            check(selectedIndex in availableIndices) { "Torrent did not contain any streamable files" }

            torrentMutex.withLock {
                val priorities = Priority.array(Priority.IGNORE, files.numFiles()).apply {
                    this[selectedIndex] = Priority.SEVEN
                }
                val torrentId = torrentCacheKey(metadata)
                pruneCache(torrentRuntime, protectedIds = setOf(torrentId))
                val requestedSaveDirectory = File(cacheRoot, torrentId).apply {
                    check(mkdirs() || isDirectory) { "Unable to create torrent cache directory" }
                    setLastModified(System.currentTimeMillis())
                }
                val existingHandle = torrentRuntime.manager.find(metadata)?.takeIf(TorrentHandle::isValid)
                val handle = if (existingHandle != null) {
                    Log.d(TAG, "Reusing torrent handle for $torrentId")
                    existingHandle
                } else {
                    Log.d(TAG, "Loading torrent into stable cache $torrentId")
                    torrentRuntime.manager.download(
                        metadata,
                        requestedSaveDirectory,
                        null,
                        priorities,
                        null,
                        TorrentFlags.SEQUENTIAL_DOWNLOAD,
                    )
                    waitForHandle(torrentRuntime.manager, metadata)
                }
                handle.prioritizeFiles(priorities)
                handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle.resume()
                runCatching { handle.scrapeTracker() }

                val saveDirectory = File(handle.savePath()).canonicalFile
                val streamFile = File(saveDirectory, files.filePath(selectedIndex)).canonicalFile
                check(streamFile.path.startsWith(saveDirectory.path + File.separator)) {
                    "Torrent file resolved outside its cache directory"
                }
                val token = UUID.randomUUID().toString()
                torrentRuntime.server.register(
                    token = token,
                    entry = TorrentStreamEntry(
                        torrentId = torrentId,
                        handle = handle,
                        files = files,
                        fileIndex = selectedIndex,
                        file = streamFile,
                        length = files.fileSize(selectedIndex),
                        mimeType = mimeTypeFor(files.fileName(selectedIndex)),
                    ),
                )
                TorrentResolvedStream(
                    streamUrl = "http://127.0.0.1:${torrentRuntime.server.listeningPort}/stream/$token",
                    fileName = files.fileName(selectedIndex),
                    length = files.fileSize(selectedIndex),
                )
            }
        }

    private fun streamToken(streamUrl: String): String? = streamUrl
        .substringAfter("/stream/", missingDelimiterValue = "")
        .substringBefore('?')
        .takeIf { it.isNotBlank() && '/' !in it }

    private fun torrentCacheKey(metadata: TorrentInfo): String {
        val v1 = metadata.infoHashV1()
        if (!v1.isAllZeros) return v1.toHex().lowercase()
        val v2 = metadata.infoHashV2()
        check(!v2.isAllZeros) { "Torrent metadata did not contain an info hash" }
        return v2.toHex().lowercase()
    }

    private fun pruneCache(torrentRuntime: TorrentRuntime, protectedIds: Set<String>) {
        val limitBytes = settings.torrentCacheSizeMb * 1024L * 1024L
        val activeIds = torrentRuntime.server.activeTorrentIds() + protectedIds
        val directories = cacheRoot.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy(File::lastModified)
        var totalBytes = directories.sumOf(::directorySize)
        for (directory in directories) {
            if (totalBytes <= limitBytes) break
            if (directory.name in activeIds) continue
            torrentRuntime.manager.getTorrentHandles()
                .firstOrNull { handle ->
                    handle.isValid && runCatching { torrentCacheKey(handle.torrentFile()) == directory.name }.getOrDefault(false)
                }
                ?.let { handle ->
                    runCatching {
                        handle.clearPieceDeadlines()
                        handle.pause()
                        torrentRuntime.manager.remove(handle)
                        waitUntilRemoved(handle)
                    }
                }
            val bytes = directorySize(directory)
            if (directory.deleteRecursively()) totalBytes -= bytes
        }
    }

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)

    private fun waitUntilRemoved(handle: TorrentHandle) {
        repeat(REMOVE_WAIT_ATTEMPTS) {
            if (!handle.inSession()) return
            Thread.sleep(REMOVE_WAIT_INTERVAL_MILLIS)
        }
    }

    private suspend fun ensureRuntime(): TorrentRuntime = runtimeMutex.withLock {
        runtime?.let { return it }
        check(cacheRoot.mkdirs() || cacheRoot.isDirectory) { "Unable to create torrent cache root" }
        val manager = SessionManager(false)
        manager.start()
        val server = TorrentHttpServer().apply {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (error: Throwable) {
                manager.stop()
                throw error
            }
        }
        check(server.listeningPort > 0) { "Unable to start torrent HTTP server" }
        return TorrentRuntime(manager, server).also { runtime = it }
    }

    private fun loadMetadata(
        manager: SessionManager,
        locator: String,
        headers: Map<String, String>,
    ): TorrentInfo {
        if (locator.startsWith("magnet:", ignoreCase = true)) {
            TorrentMetadataRegistry.find(locator)?.let { metadata ->
                Log.d(TAG, "Using cached torrent metadata for magnet playback")
                return TorrentInfo(metadata)
            }
            val metadata = manager.fetchMagnet(locator, MAGNET_METADATA_TIMEOUT_SECONDS, cacheRoot)
                ?: error("Timed out while fetching magnet metadata")
            return TorrentInfo(metadata)
        }
        val request = Request.Builder()
            .url(locator)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Torrent metadata request failed: ${response.code}" }
            val declaredLength = response.body.contentLength()
            check(declaredLength < 0 || declaredLength <= MAX_TORRENT_METADATA_BYTES) {
                "Torrent metadata was too large"
            }
            val bytes = response.body.bytes()
            check(bytes.size <= MAX_TORRENT_METADATA_BYTES) { "Torrent metadata was too large" }
            TorrentInfo(bytes)
        }
    }

    private fun waitForHandle(manager: SessionManager, metadata: TorrentInfo): TorrentHandle {
        val deadline = System.nanoTime() + HANDLE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            manager.find(metadata)?.takeIf(TorrentHandle::isValid)?.let { return it }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while adding torrent", error)
            }
        }
        error("Timed out while adding torrent")
    }

    private fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private data class TorrentRuntime(
        val manager: SessionManager,
        val server: TorrentHttpServer,
    )

    private data class TorrentStreamEntry(
        val torrentId: String,
        val handle: TorrentHandle,
        val files: FileStorage,
        val fileIndex: Int,
        val file: File,
        val length: Long,
        val mimeType: String,
    )

    private class TorrentHttpServer : NanoHTTPD("127.0.0.1", 0) {
        private val streams = ConcurrentHashMap<String, TorrentStreamEntry>()

        fun register(token: String, entry: TorrentStreamEntry) {
            streams[token] = entry
        }

        fun unregister(token: String): String? {
            val removed = streams.remove(token) ?: return null
            if (streams.values.none { it.torrentId == removed.torrentId }) {
                runCatching {
                    removed.handle.clearPieceDeadlines()
                    removed.handle.pause()
                }
            }
            return removed.torrentId
        }

        fun activeTorrentIds(): Set<String> = streams.values.mapTo(mutableSetOf(), TorrentStreamEntry::torrentId)

        fun pause(token: String) {
            streams[token]?.handle?.takeIf(TorrentHandle::isValid)?.let { handle ->
                runCatching { handle.pause() }
            }
        }

        fun resume(token: String) {
            streams[token]?.handle?.takeIf(TorrentHandle::isValid)?.let { handle ->
                runCatching { handle.resume() }
            }
        }

        fun peerStats(token: String): TorrentPeerStats? {
            val handle = streams[token]?.handle?.takeIf(TorrentHandle::isValid) ?: return null
            return runCatching {
                val status = handle.status()
                TorrentPeerStats(
                    connectedSeeds = status.numSeeds().coerceAtLeast(0),
                    totalSeeds = status.numComplete().takeIf { it >= 0 },
                )
            }.getOrNull()
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
            }
            val token = session.uri.removePrefix("/stream/").takeIf {
                session.uri.startsWith("/stream/") && '/' !in it
            } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            val entry = streams[token]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Stream not found")
            return runCatching { serveEntry(session, entry) }.getOrElse { error ->
                Log.e(TAG, "Failed to serve torrent stream", error)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Torrent stream failed")
            }
        }

        private fun serveEntry(session: IHTTPSession, entry: TorrentStreamEntry): Response {
            val rangeHeader = session.headers["range"]
            val range = if (rangeHeader == null) {
                0L..<entry.length
            } else {
                parseTorrentHttpRange(rangeHeader, entry.length)
                    ?: return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        "text/plain",
                        "Invalid range",
                    ).apply {
                        addHeader("Content-Range", "bytes */${entry.length}")
                    }
            }
            val contentLength = range.last - range.first + 1
            val status = if (rangeHeader == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
            val response = if (session.method == Method.HEAD) {
                newFixedLengthResponse(status, entry.mimeType, "")
            } else {
                newFixedLengthResponse(
                    status,
                    entry.mimeType,
                    TorrentFileInputStream(entry, range.first, contentLength),
                    contentLength,
                )
            }
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", contentLength.toString())
            if (status == Response.Status.PARTIAL_CONTENT) {
                response.addHeader("Content-Range", "bytes ${range.first}-${range.last}/${entry.length}")
            }
            return response
        }
    }

    private class TorrentFileInputStream(
        private val entry: TorrentStreamEntry,
        start: Long,
        length: Long,
    ) : InputStream() {
        private var position = start
        private var remaining = length
        private var file: RandomAccessFile? = null

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val readLength = min(length.toLong(), remaining).toInt()
            awaitPieces(position, readLength)
            val source = file ?: RandomAccessFile(entry.file, "r").also { file = it }
            source.seek(position)
            val read = source.read(buffer, offset, readLength)
            if (read > 0) {
                position += read
                remaining -= read
            }
            return read
        }

        private fun awaitPieces(fileOffset: Long, byteCount: Int) {
            check(entry.handle.isValid) { "Torrent handle is no longer valid" }
            val pieceLength = entry.files.pieceLength().toLong()
            val absoluteStart = entry.files.fileOffset(entry.fileIndex) + fileOffset
            val firstPiece = (absoluteStart / pieceLength).toInt()
            val lastPiece = ((absoluteStart + byteCount - 1) / pieceLength).toInt()
            val finalPiece = entry.files.numPieces() - 1
            for (piece in firstPiece..min(firstPiece + PIECE_LOOKAHEAD, finalPiece)) {
                if (!entry.handle.havePiece(piece)) {
                    entry.handle.piecePriority(piece, Priority.SEVEN)
                    entry.handle.setPieceDeadline(piece, (piece - firstPiece) * PIECE_DEADLINE_STEP_MILLIS)
                }
            }
            val deadline = System.nanoTime() + PIECE_TIMEOUT_NANOS
            for (piece in firstPiece..lastPiece) {
                while (!entry.handle.havePiece(piece)) {
                    if (System.nanoTime() >= deadline) throw IOException("Timed out waiting for torrent data")
                    try {
                        Thread.sleep(POLL_INTERVAL_MILLIS)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted while waiting for torrent data", error)
                    }
                }
            }
        }

        override fun close() {
            file?.close()
            file = null
            super.close()
        }
    }

    private companion object {
        const val TAG = "TorrentStreamService"
        const val TORRENT_CACHE_DIRECTORY = "torrent_tmp"
        const val MAGNET_METADATA_TIMEOUT_SECONDS = 90
        const val MAX_TORRENT_METADATA_BYTES = 16 * 1024 * 1024
        const val POLL_INTERVAL_MILLIS = 100L
        const val PIECE_LOOKAHEAD = 16
        const val PIECE_DEADLINE_STEP_MILLIS = 100
        const val REMOVE_WAIT_ATTEMPTS = 20
        const val REMOVE_WAIT_INTERVAL_MILLIS = 25L
        const val HANDLE_TIMEOUT_NANOS = 15_000_000_000L
        const val PIECE_TIMEOUT_NANOS = 300_000_000_000L
        val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "https://tracker2.ctix.cn/announce",
            "https://tracker1.520.jp:443/announce",
        )
    }
}

data class TorrentPeerStats(
    val connectedSeeds: Int,
    val totalSeeds: Int?,
)

data class TorrentResolvedStream(
    val streamUrl: String,
    val fileName: String,
    val length: Long,
)

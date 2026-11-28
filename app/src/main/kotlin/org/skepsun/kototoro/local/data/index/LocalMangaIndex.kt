package org.skepsun.kototoro.local.data.index

import android.content.Context
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LocalContentIndex @Inject constructor(
    private val mangaDataRepository: ContentDataRepository,
    private val db: MangaDatabase,
    @ApplicationContext private val context: Context,
    private val localContentRepositoryProvider: Provider<LocalMangaRepository>,
    private val storageManager: LocalStorageManager,
) : FlowCollector<LocalContent?> {

private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
private val mutex = Mutex()

/**
 * 文件 uri → 修改时间。
 *
 * 列表排序要读每个条目的修改时间，SAF 下每次都是一次 ContentResolver 查询；条目多时
 * 首屏会被几百次 IPC 拖到秒级。这里做两层缓存：进程内 Map + 落盘一份 TSV，
 * 冷启动可直接复用上次结果，只有显式重扫（下拉刷新）才让它失效。
 */
private val modifiedTimeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val modifiedTimeFile = File(context.cacheDir, "local_modified_times.tsv")
private val modifiedTimeLock = Any()

@Volatile
private var modifiedTimesLoaded = false

@Volatile
private var modifiedTimesDirty = false

private val currentVersion: Int
    get() = prefs.getInt(KEY_VERSION, 0)

override suspend fun emit(value: LocalContent?) {
    if (value != null) {
        put(value)
    }
}

suspend fun update() = mutex.withLock {
    // 显式重扫：目录内容可能变了，之前记下的时间不再可信。
    clearModifiedTimes()
    updateIndex(readableRootKeys())
}

private suspend fun updateIndex(rootKeys: Set<String>) {
    val dao = db.getLocalContentIndexDao()
    val indexed = HashSet<Long>()
    // One transaction per entry: a failed write inside a nested transaction marks the outer one for
    // rollback even when caught, so a single bad entry (e.g. a foreign key violation) used to fail the
    // whole rebuild on every list load. Stale rows are removed afterwards instead of clearing up front.
    val index = suspend { content: LocalContent ->
        runCatchingCancellable {
            db.withTransaction { upsert(content) }
        }.onSuccess {
            indexed.add(it)
        }.onFailure {
            android.util.Log.w(
                TAG,
                "Skipped local entry id=${content.manga.id} source=${content.manga.source.name} " +
                    "uri=${content.toUri()}: ${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }
    localContentRepositoryProvider.get()
        .getRawListAsFlow()
        .collect { index(it) }
    db.withTransaction {
        for (row in dao.findAllPaths()) {
            if (row.mangaId !in indexed) {
                dao.delete(row.mangaId)
            }
        }
    }
    // Capture roots before scanning so a concurrent directory change remains pending.
    prefs.edit {
        putInt(KEY_VERSION, VERSION)
        putStringSet(KEY_ROOTS, rootKeys)
    }
}

    /**
     * 索引过期时才重建，且**不会排队等待**正在进行的重建：
     * 列表读取必须立刻用库里已有的数据渲染，否则一次全量扫描就会把首屏卡住。
     * 若此刻正有扫描在跑，本次直接返回，等它提交后下一轮读取自然能看到新索引。
     */
    suspend fun updateIfRequired() {
        if (!mutex.tryLock()) {
            return
        }
        try {
            val roots = readableRootKeys()
            if (currentVersion < VERSION || prefs.getStringSet(KEY_ROOTS, null) != roots) {
                updateIndex(roots)
            }
        } finally {
            mutex.unlock()
        }
    }

    // Import notifications also drive this index's writer. List consumers must wait for the
    // committed index change rather than racing the writer on the same notification.
    fun observeChanges(): Flow<Unit> = db.invalidationTracker
        .createFlow("local_index", emitInitialState = false)
        .map { Unit }

    suspend fun get(mangaId: Long, withDetails: Boolean): LocalContent? {
        updateIfRequired()
        var path = db.getLocalContentIndexDao().findPath(mangaId)
        if (path == null && mutex.isLocked) { // wait for updating complete
            path = mutex.withLock { db.getLocalContentIndexDao().findPath(mangaId) }
        }
        if (path == null) {
            return null
        }
    return runCatchingCancellable {
        val uri = path.toUri()
        if (uri.scheme == null) {
            val dir = File(path)
            LocalContentParser(dir).getContent(withDetails)
        } else {
            // A downloaded folder keeps the remote url in its manga, so the indexed path is the location.
            LocalContentParser(uri).getContent(withDetails).copy(storageUri = uri)
        }
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()
}

    fun getCachedModifiedTime(uri: String): Long? {
        ensureModifiedTimesLoaded()
        return modifiedTimeCache[uri]
    }

    fun putCachedModifiedTime(uri: String, value: Long) {
        if (value <= 0L) {
            return
        }
        ensureModifiedTimesLoaded()
        modifiedTimeCache[uri] = value
        modifiedTimesDirty = true
    }

    /** 把新解析出的时间写回磁盘；失败无所谓，下次重新解析即可。 */
    suspend fun flushModifiedTimes() {
        if (!modifiedTimesDirty) {
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(modifiedTimeLock) {
                if (!modifiedTimesDirty) {
                    return@synchronized
                }
                val snapshot = modifiedTimeCache.toMap()
                runCatching {
                    val tmp = File(modifiedTimeFile.parentFile, "${modifiedTimeFile.name}.tmp")
                    tmp.bufferedWriter().use { writer ->
                        snapshot.forEach { (key, value) ->
                            writer.write(key)
                            writer.write("\t")
                            writer.write(value.toString())
                            writer.newLine()
                        }
                    }
                    if (!tmp.renameTo(modifiedTimeFile)) {
                        tmp.copyTo(modifiedTimeFile, overwrite = true)
                        tmp.delete()
                    }
                }
                modifiedTimesDirty = false
            }
        }
    }

    private fun ensureModifiedTimesLoaded() {
        if (modifiedTimesLoaded) {
            return
        }
        synchronized(modifiedTimeLock) {
            if (modifiedTimesLoaded) {
                return
            }
            runCatching {
                if (modifiedTimeFile.exists()) {
                    modifiedTimeFile.useLines { lines ->
                        for (line in lines) {
                            val separator = line.indexOf('\t')
                            if (separator <= 0) {
                                continue
                            }
                            val value = line.substring(separator + 1).toLongOrNull() ?: continue
                            if (value > 0L) {
                                modifiedTimeCache[line.substring(0, separator)] = value
                            }
                        }
                    }
                }
            }
            modifiedTimesLoaded = true
        }
    }

    private fun clearModifiedTimes() {
        synchronized(modifiedTimeLock) {
            modifiedTimeCache.clear()
            modifiedTimesLoaded = true
            modifiedTimesDirty = false
            runCatching { modifiedTimeFile.delete() }
        }
    }

    /** 索引里是否有任何条目；用于区分「真的没有本地内容」和「还没扫描过」。 */
    suspend fun hasEntries(): Boolean = db.getLocalContentIndexDao().findAllPaths().isNotEmpty()

    suspend operator fun contains(mangaId: Long): Boolean {
        return db.getLocalContentIndexDao().findPath(mangaId) != null
    }

    suspend fun put(manga: LocalContent) = mutex.withLock {
        db.withTransaction {
            upsert(manga)
        }
    }

    suspend fun delete(mangaId: Long) = mutex.withLock {
        db.getLocalContentIndexDao().delete(mangaId)
    }

    suspend fun getAvailableTags(skipNsfw: Boolean): List<String> {
        val dao = db.getLocalContentIndexDao()
        return if (skipNsfw) {
            dao.findTags(isNsfw = false)
        } else {
            dao.findTags()
        }
    }

    private suspend fun upsert(manga: LocalContent): Long {
        // Local chapter URLs describe payloads on disk. Keep them out of the remote catalog.
        val stored = mangaDataRepository.storeContentAndReturn(
            manga.manga.copy(chapters = null),
            replaceExisting = true,
        )
        db.getLocalContentIndexDao().upsert(
            LocalContentIndexEntity(
                mangaId = stored.id,
                path = manga.toUri().toString(),
            ),
        )
        return stored.id
    }

    private fun LocalContent.toEntity() = LocalContentIndexEntity(
        mangaId = manga.id,
        path = toUri().toString(),
    )

    private suspend fun readableRootKeys(): Set<String> =
        storageManager.getAllReadableRoots().mapTo(HashSet()) { it.key }

    companion object {

        private const val TAG = "LocalContentIndex"
        private const val PREF_NAME = "_local_index"
        private const val KEY_VERSION = "ver"
        private const val KEY_ROOTS = "readable_roots"
        private const val VERSION = 6
    }
}

package org.skepsun.kototoro.local.data.index

import android.content.Context
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.novel.LocalNovelRepository
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
    @ApplicationContext context: Context,
    private val localContentRepositoryProvider: Provider<LocalMangaRepository>,
    private val localNovelRepositoryProvider: Provider<LocalNovelRepository>,
    private val storageManager: LocalStorageManager,
) : FlowCollector<LocalContent?> {

private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
private val mutex = Mutex()

private val currentVersion: Int
    get() = prefs.getInt(KEY_VERSION, 0)

override suspend fun emit(value: LocalContent?) {
    if (value != null) {
        put(value)
    }
}

suspend fun update() = mutex.withLock {
    updateIndex(readableRootKeys())
}

private suspend fun updateIndex(rootKeys: Set<String>) {
    db.withTransaction {
        val dao = db.getLocalContentIndexDao()
        dao.clear()
        localContentRepositoryProvider.get()
            .getRawListAsFlow()
            .collect { upsert(it) }
        // novels
        localNovelRepositoryProvider.get()
            .getAllLocalNovels()
            .forEach { upsert(it) }
    }
    // Capture roots before scanning so a concurrent directory change remains pending.
    prefs.edit {
        putInt(KEY_VERSION, VERSION)
        putStringSet(KEY_ROOTS, rootKeys)
    }
}

    suspend fun updateIfRequired() = mutex.withLock {
        val roots = readableRootKeys()
        if (currentVersion < VERSION || prefs.getStringSet(KEY_ROOTS, null) != roots) {
            updateIndex(roots)
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
            val novel = localNovelRepositoryProvider.get().getLocalNovel(dir, withDetails)
            if (novel != null) return@runCatchingCancellable novel
            LocalContentParser(dir).getContent(withDetails)
        } else {
            LocalContentParser(uri).getContent(withDetails)
        }
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()
}

    suspend operator fun contains(mangaId: Long): Boolean {
        return db.getLocalContentIndexDao().findPath(mangaId) != null
    }

    suspend fun put(manga: LocalContent) = mutex.withLock {
        db.withTransaction {
            upsert(manga)
        }
    }

    suspend fun delete(mangaId: Long) {
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

    private suspend fun upsert(manga: LocalContent) {
        mangaDataRepository.storeContent(manga.manga, replaceExisting = true)
        db.getLocalContentIndexDao().upsert(manga.toEntity())
    }

    private fun LocalContent.toEntity() = LocalContentIndexEntity(
        mangaId = manga.id,
        path = toUri().toString(),
    )

    private suspend fun readableRootKeys(): Set<String> =
        storageManager.getAllReadableRoots().mapTo(HashSet()) { it.key }

    companion object {

        private const val PREF_NAME = "_local_index"
        private const val KEY_VERSION = "ver"
        private const val KEY_ROOTS = "readable_roots"
        private const val VERSION = 4
    }
}

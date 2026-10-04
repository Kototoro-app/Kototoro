package org.skepsun.kototoro.desktop.runtime

import androidx.room.useReaderConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.explore.data.SourcesSortOrder
import org.skepsun.kototoro.source.host.FileSourceImageStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Owns desktop storage resources. Source platform/browser initialization and UI remain separate consumers. */
class DesktopRuntime private constructor(
    val paths: DesktopDataPaths,
    private val storage: DesktopDatabase,
    val images: FileSourceImageStore,
    val preferences: FileSourcePreferenceStore,
) : Closeable {
    private val closed = AtomicBoolean()
    val database: MangaDatabase get() = storage.database

    suspend fun storageInfo(): DesktopStorageInfo {
        check(!closed.get()) { "Desktop runtime is closed" }
        val version = database.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA user_version") { statement ->
                check(statement.step())
                statement.getLong(0).toInt()
            }
        }
        val sources = database.getSourcesDao().findAll(false, SourcesSortOrder.MANUAL).size
        return DesktopStorageInfo(paths.root.toString(), paths.database.toString(), images.directory.toString(),
            version, sources, preferences.directory.toString())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try { preferences.close() } catch (error: Throwable) { failure = error }
        try { storage.close() } catch (error: Throwable) {
            if (failure != null) failure.addSuppressed(error) else failure = error
        }
        failure?.let { throw it }
    }

    companion object {
        suspend fun open(root: Path = DesktopDataPaths.defaultRoot()): DesktopRuntime {
            var created: DesktopRuntime? = null
            try {
                return withContext(Dispatchers.IO) { openOnIo(root).also { created = it } }
            } catch (error: Throwable) {
                // withContext can discard an acquired runtime on prompt cancellation during dispatcher return.
                try { created?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }

        private suspend fun openOnIo(root: Path): DesktopRuntime {
            val paths = DesktopDataPaths.create(root)
            val storage = DesktopDatabase.create(paths.database)
            try {
                // Room is lazy: validate schema/migrations before publishing a usable runtime or creating image cache.
                storage.database.getSourcesDao().getMaxSortKey()
                currentCoroutineContext().ensureActive()
                val images = FileSourceImageStore(paths.images)
                val preferences = FileSourcePreferenceStore(paths.preferences)
                return DesktopRuntime(paths, storage, images, preferences)
            } catch (error: Throwable) {
                try { storage.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}

@Serializable
data class DesktopStorageInfo(
    val dataDirectory: String,
    val database: String,
    val imageDirectory: String,
    val schemaVersion: Int,
    val sourceCount: Int,
    val preferenceDirectory: String,
)

package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.toList
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.sync.google.data.model.*
import org.skepsun.kototoro.sync.google.domain.SyncContentStore
import org.skepsun.kototoro.sync.google.domain.importSyncContent
import org.skepsun.kototoro.tracker.data.mergeRestoredTrack
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackLog

/** Applies Android's library protocol atomically. Platform-specific sections remain in the upload unchanged. */
class LibraryDriveSync(private val database: MangaDatabase) {
    suspend fun merge(remote: GoogleDriveSyncSnapshot?, deviceId: String, now: Long): GoogleDriveSyncSnapshot =
        database.useWriterConnection { connection -> connection.immediateTransaction {
            val snapshot = remote?.normalizeToContentV3()
            if (snapshot != null) apply(snapshot)
            val history = database.getHistoryDao().findAllEntriesIncludingDeleted()
            val favourites = database.getFavouritesDao().findAllEntriesIncludingDeleted()
            val stats = database.getStatsDao().dump().toList()
            val tracks = database.getTracksDao().dump()
            val logs = database.getTrackLogsDao().dump()
            val ids = (history.map { it.mangaId } + favourites.map { it.mangaId } + stats.map { it.mangaId } +
                tracks.map { it.mangaId } + logs.map { it.mangaId }).toSet()
            GoogleDriveSyncSnapshot(
                deviceId = deviceId,
                syncedAt = now,
                content = ids.chunked(500).flatMap { database.getMangaDao().findEntitiesByIds(it) }.map(::SyncContent),
                categories = database.getFavouriteCategoriesDao().dump().map(::SyncFavouriteCategory),
                history = history.map(::SyncHistory),
                favourites = favourites.map(::SyncFavourite),
                stats = stats.map(::SyncStats),
                feed = SyncFeedState(tracks.map(::SyncTrack), logs.map(::SyncTrackLog)),
                config = snapshot?.config,
                repositories = snapshot?.repositories.orEmpty(),
                sourceStates = snapshot?.sourceStates.orEmpty(),
                jsonSources = snapshot?.jsonSources.orEmpty(),
                extensions = snapshot?.extensions.orEmpty(),
            )
        } }

    private suspend fun apply(snapshot: GoogleDriveSyncSnapshot) {
        require(snapshot.categories.all { it.id in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) {
            "云端分类编号超出支持范围，已停止同步"
        }
        // Import before merging state: the same numeric id can identify different works on two devices.
        val imported = importSyncContent(snapshot.content, contentStore())
        val categories = database.getFavouriteCategoriesDao()
        for (remote in snapshot.categories) {
            val local = categories.findIncludingDeleted(remote.id)
            if (local == null || remote.deletedAt > local.deletedAt ||
                (remote.deletedAt == local.deletedAt && remote.createdAt > local.createdAt)) {
                categories.upsert(remote.toEntity())
            }
        }
        for (remote in snapshot.history) {
            val id = imported.localIdOf(remote.mangaId) ?: continue
            if (!database.getMangaDao().contains(id)) continue
            val local = database.getHistoryDao().findIncludingDeleted(id)
            if (local == null || remote.updatedAt > local.updatedAt ||
                (remote.updatedAt == local.updatedAt && remote.deletedAt > local.deletedAt)) {
                database.getHistoryDao().upsertSync(remote.toEntity(id))
            }
        }
        for (remote in snapshot.favourites) {
            val id = imported.localIdOf(remote.mangaId) ?: continue
            if (!database.getMangaDao().contains(id)) continue
            require(categories.findIncludingDeleted(remote.categoryId) != null) { "云端收藏缺少对应分类，已停止同步" }
            val local = database.getFavouritesDao().find(id, remote.categoryId)
            if (local == null || remote.updatedAt > local.updatedAt ||
                (remote.updatedAt == local.updatedAt && remote.deletedAt > local.deletedAt)) {
                database.getFavouritesDao().upsert(remote.toEntity(id))
            }
        }
        for (remote in snapshot.stats) {
            val id = imported.localIdOf(remote.mangaId) ?: continue
            if (database.getMangaDao().contains(id) && database.getStatsDao().find(id, remote.startedAt) == null) {
                database.getStatsDao().upsert(remote.toEntity(id))
            }
        }
        for (remote in snapshot.feed.tracks) {
            val id = imported.localIdOf(remote.mangaId) ?: continue
            if (database.getMangaDao().contains(id)) database.mergeRestoredTrack(remote.toEntity(id))
        }
        for (remote in snapshot.feed.logs) {
            val id = imported.localIdOf(remote.mangaId) ?: continue
            if (database.getMangaDao().contains(id)) database.mergeRestoredTrackLog(remote.toEntity(id))
        }
    }

    private fun contentStore() = object : SyncContentStore {
        private val dao = database.getMangaDao()
        override suspend fun findByIdentity(content: SyncContent): MangaEntity? {
            for (url in listOf(content.url, content.publicUrl).filter { it.isNotBlank() }.distinct()) {
                dao.findBySourceAndUrl(content.source, url)?.manga?.let { return it }
                dao.findBySourceAndPublicUrl(content.source, url)?.manga?.let { return it }
            }
            return null
        }
        override suspend fun findById(id: Long) = dao.find(id)?.manga
        override suspend fun contains(id: Long) = dao.contains(id)
        override suspend fun findMinId() = dao.findMinId()
        override suspend fun upsert(entity: MangaEntity) = dao.upsert(entity)
    }
}

package org.skepsun.kototoro.sync.google.domain

import org.skepsun.kototoro.core.model.ContentIdentityKeys
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncConfig
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionRepo
import org.skepsun.kototoro.sync.google.data.model.SyncJsonSource
import org.skepsun.kototoro.sync.google.data.model.SyncSourceState
import org.skepsun.kototoro.sync.google.data.model.SyncFeedState
import org.skepsun.kototoro.sync.google.data.model.SyncTrackLog
import org.skepsun.kototoro.sync.google.data.model.SyncTrack
import org.skepsun.kototoro.sync.google.data.model.SyncStats
import org.skepsun.kototoro.sync.google.data.model.SyncFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncHistory
import org.skepsun.kototoro.tracker.data.isNewerThan

object GoogleDriveSyncMerger {

    fun combine(snapshots: List<GoogleDriveSyncSnapshot>): GoogleDriveSyncSnapshot? = when {
        snapshots.isEmpty() -> null
        snapshots.size == 1 -> compactSnapshot(snapshots.single().normalizeToContentV3())
        else -> snapshots.map { it.normalizeToContentV3() }.reduce(::mergeSnapshots)
    }

    fun mergeSnapshots(
        local: GoogleDriveSyncSnapshot,
        remote: GoogleDriveSyncSnapshot?,
    ): GoogleDriveSyncSnapshot {
        if (remote == null) {
            return compactSnapshot(local.normalizeToContentV3())
        }
        val normLocal = local.normalizeToContentV3()
        val normRemote = remote.normalizeToContentV3()
        val config = mergeConfig(normLocal.config, normRemote.config)
        return compactSnapshot(
            GoogleDriveSyncSnapshot(
                schemaVersion = GoogleDriveSyncSnapshot.SCHEMA_VERSION,
                namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
                semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
                deviceId = normLocal.deviceId,
                syncedAt = maxOf(normLocal.syncedAt, normRemote.syncedAt),
                content = normLocal.content + normRemote.content,
                categories = normLocal.categories + normRemote.categories,
                history = normLocal.history + normRemote.history,
                favourites = normLocal.favourites + normRemote.favourites,
                stats = normLocal.stats + normRemote.stats,
                feed = SyncFeedState(
                    tracks = normLocal.feed.tracks + normRemote.feed.tracks,
                    logs = normLocal.feed.logs + normRemote.feed.logs,
                ),
                config = config,
                repositories = normLocal.repositories + normRemote.repositories,
                sourceStates = normLocal.sourceStates + normRemote.sourceStates,
                jsonSources = normLocal.jsonSources + normRemote.jsonSources,
                extensions = normLocal.extensions + normRemote.extensions,
            ),
        )
    }

    private fun mergeConfig(local: SyncConfig?, remote: SyncConfig?): SyncConfig? = when {
        local == null -> remote
        remote == null -> local
        remote.revision > local.revision -> remote
        else -> local
    }

    private fun compactSnapshot(snapshot: GoogleDriveSyncSnapshot): GoogleDriveSyncSnapshot {
        val norm = snapshot.normalizeToContentV3()
        // 1. Content: rows sharing a content identity (same source + url/publicUrl) are one
        // manga, even when older snapshots carry them under different ids. Keep the lowest id
        // and re-point every piece of state at it, so the LWW passes below see one owner.
        val survivorIdById = HashMap<Long, Long>()
        val compactContent = norm.content
            .groupBy { ContentIdentityKeys.contentCompactKey(it.source, it.id, it.url, it.publicUrl) }
            .values
            .map { items ->
                val survivor = items.minBy { it.id }
                items.forEach { survivorIdById[it.id] = survivor.id }
                survivor
            }
            .distinctBy { it.id }
            .sortedBy { it.id }
        fun Long.toSurvivorId(): Long = survivorIdById[this] ?: this

        // 2. Categories: deduplicate by id, LWW
        val compactCategories = norm.categories
            .groupBy { it.id }
            .values
            .map { items ->
                items.maxByOrNull { it.createdAt } ?: items.first()
            }
            .sortedBy { it.id }

        // 3. History: deduplicate by mangaId, LWW
        val compactHistory = norm.history
            .map { it.withMangaId(it.mangaId.toSurvivorId()) }
            .groupBy { it.mangaId }
            .values
            .map { items ->
                items.maxByOrNull { it.updatedAt } ?: items.first()
            }
            .sortedByDescending { it.updatedAt }

        // 4. Favourites: deduplicate by (mangaId, categoryId), LWW
        val compactFavourites = norm.favourites
            .map { it.withMangaId(it.mangaId.toSurvivorId()) }
            .groupBy { it.mangaId to it.categoryId }
            .values
            .map { items ->
                items.maxByOrNull { it.updatedAt } ?: items.first()
            }
            .sortedByDescending { it.updatedAt }

        // 5. Stats: deduplicate by (mangaId, startedAt)
        val compactStats = norm.stats
            .map { it.withMangaId(it.mangaId.toSurvivorId()) }
            .groupBy { it.mangaId to it.startedAt }
            .values
            .map { it.first() }
            .sortedBy { it.startedAt }

        // 6. Tracks: merge tracks by mangaId
        val compactTracks = norm.feed.tracks
            .map { it.withMangaId(it.mangaId.toSurvivorId()) }
            .groupBy { it.mangaId }
            .values
            .map { items ->
                items.reduce { acc, track ->
                    if (track.toEntity().isNewerThan(acc.toEntity())) track else acc
                }
            }

        // 7. Track logs: deduplicate by (mangaId, chapters, createdAt)
        val compactLogs = norm.feed.logs
            .map { it.withMangaId(it.mangaId.toSurvivorId()) }
            .groupBy { Triple(it.mangaId, it.chapters, it.createdAt) }
            .values
            .map { it.first() }
            .sortedByDescending { it.createdAt }

        // 8. Extension repos, source states, json sources, extension packages
        val compactRepos = norm.repositories
            .groupBy { it.type to it.baseUrl }
            .values
            .map(::mergeExtensionRepos)

        val compactSources = norm.sourceStates
            .groupBy { it.source }
            .values
            .map(::mergeSourceStates)

        val compactJsonSources = norm.jsonSources
            .groupBy { it.id.ifBlank { it.name } }
            .values
            .map(::mergeJsonSources)

        val compactPackages = norm.extensions
            .groupBy { it.packageId }
            .values
            .map(::mergeExtensionPackages)

        return GoogleDriveSyncSnapshot(
            schemaVersion = GoogleDriveSyncSnapshot.SCHEMA_VERSION,
            namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
            semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
            deviceId = norm.deviceId,
            syncedAt = norm.syncedAt,
            content = compactContent,
            categories = compactCategories,
            history = compactHistory,
            favourites = compactFavourites,
            stats = compactStats,
            feed = SyncFeedState(
                tracks = compactTracks,
                logs = compactLogs,
            ),
            config = norm.config,
            repositories = compactRepos,
            sourceStates = compactSources,
            jsonSources = compactJsonSources,
            extensions = compactPackages,
        )
    }

    private fun SyncHistory.withMangaId(id: Long) = if (id == mangaId) this else SyncHistory(
        mangaId = id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chapterId = chapterId,
        page = page,
        scroll = scroll,
        percent = percent,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
        deletedAt = deletedAt,
    )

    private fun SyncFavourite.withMangaId(id: Long) = if (id == mangaId) this else SyncFavourite(
        mangaId = id,
        categoryId = categoryId,
        sortKey = sortKey,
        isPinned = isPinned,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun SyncStats.withMangaId(id: Long) = if (id == mangaId) this else SyncStats(
        mangaId = id,
        startedAt = startedAt,
        duration = duration,
        pages = pages,
    )

    private fun SyncTrack.withMangaId(id: Long) = if (id == mangaId) this else SyncTrack(
        mangaId = id,
        lastChapterId = lastChapterId,
        newChapters = newChapters,
        lastCheckTime = lastCheckTime,
        lastChapterDate = lastChapterDate,
        lastResult = lastResult,
        lastError = lastError,
    )

    private fun SyncTrackLog.withMangaId(id: Long) = if (id == mangaId) this else SyncTrackLog(
        mangaId = id,
        chapters = chapters,
        createdAt = createdAt,
        isUnread = isUnread,
    )

    private fun mergeExtensionRepos(items: List<SyncExtensionRepo>): SyncExtensionRepo {
        if (items.size == 1) return items.single()
        return items.maxByOrNull { it.updatedAt } ?: items.first()
    }

    private fun mergeSourceStates(items: List<SyncSourceState>): SyncSourceState {
        if (items.size == 1) return items.single()
        val latest = items.maxByOrNull { it.usedAt } ?: items.first()
        val anyEnabled = items.any { it.isEnabled }
        val anyPinned = items.any { it.isPinned }
        val maxSortKey = items.maxOf { it.sortKey }
        return SyncSourceState(
            source = latest.source,
            isEnabled = anyEnabled,
            isPinned = anyPinned,
            sortKey = maxSortKey,
            usedAt = latest.usedAt,
        )
    }

    private fun mergeJsonSources(items: List<SyncJsonSource>): SyncJsonSource {
        if (items.size == 1) return items.single()
        return items.maxByOrNull { it.lastUsedAt } ?: items.first()
    }

    private fun mergeExtensionPackages(items: List<SyncExtensionPackage>): SyncExtensionPackage {
        if (items.size == 1) return items.single()
        val withPayload = items.filter { it.isPayloadIncluded && !it.payloadBase64.isNullOrBlank() }
        if (withPayload.isNotEmpty()) {
            return withPayload.maxByOrNull { it.versionCode ?: 0L } ?: withPayload.first()
        }
        return items.maxByOrNull { it.versionCode ?: 0L } ?: items.first()
    }
}

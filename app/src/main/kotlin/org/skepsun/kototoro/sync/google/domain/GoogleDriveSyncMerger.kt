package org.skepsun.kototoro.sync.google.domain

import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncConfig
import org.skepsun.kototoro.sync.google.data.model.SyncContent
import org.skepsun.kototoro.sync.google.data.model.SyncEntityBindingRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityGraph
import org.skepsun.kototoro.sync.google.data.model.SyncEntityPrefsRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityRelationRecord
import org.skepsun.kototoro.sync.google.data.model.SyncFeedState
import org.skepsun.kototoro.sync.google.data.model.SyncFavouriteCategory
import org.skepsun.kototoro.sync.google.data.model.SyncTrack
import org.skepsun.kototoro.sync.google.data.model.SyncTrackLog
import org.skepsun.kototoro.sync.google.data.model.SyncWorkFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncWorkHistory
import org.skepsun.kototoro.sync.google.data.model.SyncWorkState
import org.skepsun.kototoro.sync.google.data.model.SyncWorkStats
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.isNewerThan
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackNewChapters

object GoogleDriveSyncMerger {

	fun combine(snapshots: List<GoogleDriveSyncSnapshot>): GoogleDriveSyncSnapshot? = when {
		snapshots.isEmpty() -> null
		snapshots.size == 1 -> snapshots.single()
		else -> snapshots.reduce(::mergeSnapshots)
	}

	fun mergeSnapshots(
		local: GoogleDriveSyncSnapshot,
		remote: GoogleDriveSyncSnapshot?,
	): GoogleDriveSyncSnapshot {
		if (remote == null) {
			return local
		}
		val config = mergeConfig(local.config, remote.config)
		return GoogleDriveSyncSnapshot(
			deviceId = local.deviceId,
			syncedAt = maxOf(local.syncedAt, remote.syncedAt),
			entityGraph = mergeEntityGraph(local.entityGraph, remote.entityGraph),
			content = mergeContent(local.content, remote.content),
			work = mergeWork(local.work, remote.work),
			feed = mergeFeed(local.feed, remote.feed),
			config = config,
		)
	}

	private fun mergeConfig(local: SyncConfig?, remote: SyncConfig?): SyncConfig? = when {
		local == null -> remote
		remote == null -> local
		remote.revision > local.revision -> remote
		else -> local
	}

	private fun mergeContent(
		local: List<SyncContent>,
		remote: List<SyncContent>,
	): List<SyncContent> {
		return (local + remote)
			.distinctBy { it.publicUrl.ifBlank { "${it.source}\n${it.url}\n${it.id}" } }
			.sortedBy { it.id }
	}

	private fun mergeEntityGraph(local: SyncEntityGraph, remote: SyncEntityGraph): SyncEntityGraph {
		return SyncEntityGraph(
			entities = (local.entities + remote.entities)
				.groupBy { EntityKey(it.type, it.primaryName.trim().lowercase()) }
				.values
				.map(::mergeEntities)
				.sortedBy { it.id },
			bindings = (local.bindings + remote.bindings)
				.groupBy { BindingKey(it.source, it.externalId) }
				.values
				.map(::mergeBindings)
				.sortedWith(compareBy<SyncEntityBindingRecord> { it.source }.thenBy { it.externalId }),
			relations = (local.relations + remote.relations)
				.distinctBy { RelationKey(it.fromEntityId, it.toEntityId, it.type) }
				.sortedWith(compareBy<SyncEntityRelationRecord> { it.fromEntityId }.thenBy { it.toEntityId }.thenBy { it.type }),
			prefs = (local.prefs + remote.prefs)
				.groupBy { it.entityId }
				.values
				.map(::mergePrefs)
				.sortedBy { it.entityId },
		)
	}

	private fun mergeEntities(items: List<SyncEntityRecord>): SyncEntityRecord {
		return items.reduce { left, right ->
			val newer = if (right.lastAccessed > left.lastAccessed) right else left
			SyncEntityRecord(
				id = left.id,
				type = left.type,
				primaryName = newer.primaryName,
				nameHash = newer.nameHash,
				aliases = newer.aliases ?: left.aliases,
				createdAt = minOf(left.createdAt, right.createdAt),
				lastAccessed = maxOf(left.lastAccessed, right.lastAccessed),
				accessCount = maxOf(left.accessCount, right.accessCount),
			)
		}
	}

	private fun mergeBindings(items: List<SyncEntityBindingRecord>): SyncEntityBindingRecord {
		return items.maxBy { it.updatedAt }
	}

	private fun mergePrefs(items: List<SyncEntityPrefsRecord>): SyncEntityPrefsRecord {
		return items.maxBy { it.updatedAt }
	}

	private fun mergeWork(local: SyncWorkState, remote: SyncWorkState): SyncWorkState {
		return SyncWorkState(
			categories = (local.categories + remote.categories)
				.groupBy { it.id }
				.values
				.map(::mergeCategories)
				.sortedBy { it.sortKey },
			history = (local.history + remote.history)
				.groupBy { it.entityId }
				.values
				.map(::mergeWorkHistory)
				.sortedByDescending { it.updatedAt },
			favourites = (local.favourites + remote.favourites)
				.groupBy { WorkFavouriteKey(it.entityId, it.categoryId) }
				.values
				.map(::mergeWorkFavourite)
				.sortedWith(compareBy<SyncWorkFavourite> { it.categoryId }.thenBy { it.sortKey }),
			stats = (local.stats + remote.stats)
				.distinctBy { WorkStatsKey(it.entityId, it.startedAt) }
				.sortedByDescending { it.startedAt },
		)
	}

	private fun mergeCategories(items: List<SyncFavouriteCategory>): SyncFavouriteCategory {
		return items.maxBy { it.deletedAt }
	}

	private fun mergeWorkHistory(items: List<SyncWorkHistory>): SyncWorkHistory {
		return items.maxBy { it.updatedAt }
	}

	private fun mergeWorkFavourite(items: List<SyncWorkFavourite>): SyncWorkFavourite {
		return items.maxBy { it.updatedAt }
	}

	private fun mergeFeed(local: SyncFeedState, remote: SyncFeedState): SyncFeedState {
		return SyncFeedState(
			tracks = (local.tracks + remote.tracks)
				.groupBy { it.ownerId }
				.values
				.map(::mergeTracks)
				.sortedWith(compareByDescending<SyncTrack> { it.lastChapterDate }.thenByDescending { it.lastCheckTime }),
			logs = (local.logs + remote.logs)
				.groupBy { TrackLogKey(it.ownerId, it.mangaId, it.entityId, it.chapters, it.createdAt) }
				.values
				.map(::mergeTrackLogs)
				.sortedByDescending { it.createdAt },
		)
	}

	private fun mergeTracks(items: List<SyncTrack>): SyncTrack {
		return items.reduce { left, right -> left.mergeWith(right) }
	}

	private fun SyncTrack.mergeWith(remote: SyncTrack): SyncTrack {
		val localEntity = toEntity()
		val remoteEntity = remote.toEntity()
		val newer = if (remoteEntity.isNewerThan(localEntity)) remote else this
		val mergedLastError = when {
			newer.lastResult == TrackEntity.RESULT_FAILED -> newer.lastError
			lastResult == TrackEntity.RESULT_FAILED && remote.lastResult != TrackEntity.RESULT_FAILED -> remote.lastError
			else -> null
		}
		return SyncTrack(
			ownerId = ownerId,
			mangaId = mangaId,
			entityId = entityId ?: remote.entityId,
			lastChapterId = newer.lastChapterId,
			newChapters = mergeRestoredTrackNewChapters(localEntity, remoteEntity),
			lastCheckTime = maxOf(lastCheckTime, remote.lastCheckTime),
			lastChapterDate = maxOf(lastChapterDate, remote.lastChapterDate),
			lastResult = newer.lastResult,
			lastError = mergedLastError,
		)
	}

	private fun mergeTrackLogs(items: List<SyncTrackLog>): SyncTrackLog {
		val first = items.first()
		return SyncTrackLog(
			ownerId = first.ownerId,
			mangaId = first.mangaId,
			entityId = first.entityId,
			chapters = first.chapters,
			createdAt = first.createdAt,
			isUnread = items.all { it.isUnread },
		)
	}

	private data class TrackLogKey(
		val ownerId: Long,
		val mangaId: Long,
		val entityId: Long?,
		val chapters: String,
		val createdAt: Long,
	)

	private data class EntityKey(val type: String, val primaryName: String)
	private data class BindingKey(val source: String, val externalId: String)
	private data class RelationKey(val fromEntityId: Long, val toEntityId: Long, val type: String)
	private data class WorkFavouriteKey(val entityId: Long, val categoryId: Long)
	private data class WorkStatsKey(val entityId: Long, val startedAt: Long)
}

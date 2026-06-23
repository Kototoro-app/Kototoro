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
import org.skepsun.kototoro.tracker.data.resolveTrackOwnerId

object GoogleDriveSyncMerger {

	fun combine(snapshots: List<GoogleDriveSyncSnapshot>): GoogleDriveSyncSnapshot? = when {
		snapshots.isEmpty() -> null
		snapshots.size == 1 -> compactSnapshot(snapshots.single())
		else -> snapshots.reduce(::mergeSnapshots)
	}

	fun mergeSnapshots(
		local: GoogleDriveSyncSnapshot,
		remote: GoogleDriveSyncSnapshot?,
	): GoogleDriveSyncSnapshot {
		if (remote == null) {
			return compactSnapshot(local)
		}
		val config = mergeConfig(local.config, remote.config)
		return compactSnapshot(
			GoogleDriveSyncSnapshot(
				deviceId = local.deviceId,
				syncedAt = maxOf(local.syncedAt, remote.syncedAt),
				entityGraph = SyncEntityGraph(
					entities = local.entityGraph.entities + remote.entityGraph.entities,
					bindings = local.entityGraph.bindings + remote.entityGraph.bindings,
					relations = local.entityGraph.relations + remote.entityGraph.relations,
					prefs = local.entityGraph.prefs + remote.entityGraph.prefs,
				),
				content = local.content + remote.content,
				work = SyncWorkState(
					categories = local.work.categories + remote.work.categories,
					history = local.work.history + remote.work.history,
					favourites = local.work.favourites + remote.work.favourites,
					stats = local.work.stats + remote.work.stats,
				),
				feed = SyncFeedState(
					tracks = local.feed.tracks + remote.feed.tracks,
					logs = local.feed.logs + remote.feed.logs,
				),
				config = config,
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
		val contentIdMap = LinkedHashMap<Long, Long>()
		val compactContent = snapshot.content
			.groupBy(::contentKey)
			.values
			.map { items ->
				val canonical = items.minBy { it.id }
				items.forEach { contentIdMap[it.id] = canonical.id }
				canonical
			}
			.sortedBy { it.id }
		val entityGroups = buildEntityGroups(snapshot, contentIdMap)
		val entityIdMap = LinkedHashMap<Long, Long>()
		val compactEntities = snapshot.entityGraph.entities
			.groupBy { entity -> entityGroups.find(entity.id) }
			.values
			.map { items ->
				val merged = mergeEntities(items.sortedBy { it.id })
				items.forEach { entityIdMap[it.id] = merged.id }
				merged
			}
			.sortedBy { it.id }
		val contentIds = compactContent.mapTo(HashSet(compactContent.size)) { it.id }
		val mappedPrefs = snapshot.entityGraph.prefs
			.map { prefs ->
				SyncEntityPrefsRecord(
					entityId = entityIdMap[prefs.entityId] ?: prefs.entityId,
					preferredLocalMangaId = prefs.preferredLocalMangaId?.let { contentIdMap[it] ?: it },
					titleOverride = prefs.titleOverride,
					coverUrlOverride = prefs.coverUrlOverride,
					contentRatingOverride = prefs.contentRatingOverride,
					readingStatus = prefs.readingStatus,
					metadataSourceKind = prefs.metadataSourceKind,
					metadataBindingSource = prefs.metadataBindingSource,
					metadataBindingExternalId = prefs.metadataBindingExternalId,
					metadataSourceService = prefs.metadataSourceService,
					metadataSourceRemoteId = prefs.metadataSourceRemoteId,
					updatedAt = prefs.updatedAt,
				)
			}
			.groupBy { it.entityId }
			.values
			.map(::mergePrefs)
			.sortedBy { it.entityId }
		val mappedHistory = snapshot.work.history
			.map { history ->
				SyncWorkHistory(
					entityId = entityIdMap[history.entityId] ?: history.entityId,
					anchorMangaId = contentIdMap[history.anchorMangaId] ?: history.anchorMangaId,
					createdAt = history.createdAt,
					updatedAt = history.updatedAt,
					chapterId = history.chapterId,
					page = history.page,
					scroll = history.scroll,
					percent = history.percent,
					chaptersCount = history.chaptersCount,
					parentChapterId = history.parentChapterId,
					deletedAt = history.deletedAt,
				)
			}
			.groupBy { WorkAnchorKey(it.anchorMangaId) }
			.values
			.map(::mergeWorkHistory)
			.sortedByDescending { it.updatedAt }
		val mappedFavourites = snapshot.work.favourites
			.map { favourite ->
				SyncWorkFavourite(
					entityId = entityIdMap[favourite.entityId] ?: favourite.entityId,
					categoryId = favourite.categoryId,
					anchorMangaId = favourite.anchorMangaId?.let { contentIdMap[it] ?: it },
					sortKey = favourite.sortKey,
					isPinned = favourite.isPinned,
					createdAt = favourite.createdAt,
					updatedAt = favourite.updatedAt,
					deletedAt = favourite.deletedAt,
				)
			}
			.groupBy { favourite -> WorkFavouriteKey(favourite.anchorMangaId ?: favourite.entityId, favourite.categoryId) }
			.values
			.map(::mergeWorkFavourite)
			.sortedWith(compareBy<SyncWorkFavourite> { it.categoryId }.thenBy { it.sortKey })
		val mappedStats = snapshot.work.stats
			.map { stats ->
				SyncWorkStats(
					entityId = entityIdMap[stats.entityId] ?: stats.entityId,
					anchorMangaId = contentIdMap[stats.anchorMangaId] ?: stats.anchorMangaId,
					startedAt = stats.startedAt,
					duration = stats.duration,
					pages = stats.pages,
				)
			}
			.distinctBy { WorkStatsKey(it.entityId, it.startedAt) }
			.sortedByDescending { it.startedAt }
		val mappedTracks = snapshot.feed.tracks
			.map { track ->
				SyncTrack(
					ownerId = track.ownerId,
					mangaId = contentIdMap[track.mangaId] ?: track.mangaId,
					entityId = track.entityId?.let { entityIdMap[it] ?: it },
					lastChapterId = track.lastChapterId,
					newChapters = track.newChapters,
					lastCheckTime = track.lastCheckTime,
					lastChapterDate = track.lastChapterDate,
					lastResult = track.lastResult,
					lastError = track.lastError,
				)
			}
			.groupBy { it.mangaId }
			.values
			.map(::mergeTracks)
			.map { it.withResolvedOwnerId() }
			.sortedWith(compareByDescending<SyncTrack> { it.lastChapterDate }.thenByDescending { it.lastCheckTime })
		val mappedLogs = snapshot.feed.logs
			.map { log ->
				SyncTrackLog(
					ownerId = log.ownerId,
					mangaId = contentIdMap[log.mangaId] ?: log.mangaId,
					entityId = log.entityId?.let { entityIdMap[it] ?: it },
					chapters = log.chapters,
					createdAt = log.createdAt,
					isUnread = log.isUnread,
				)
			}
			.groupBy { TrackLogKey(it.ownerId, it.mangaId, it.entityId, it.chapters, it.createdAt) }
			.values
			.map(::mergeTrackLogs)
			.sortedByDescending { it.createdAt }
		val referencedContentIds = HashSet<Long>().apply {
			addAll(mappedHistory.map { it.anchorMangaId })
			addAll(mappedStats.map { it.anchorMangaId })
			addAll(mappedPrefs.mapNotNull { it.preferredLocalMangaId })
			addAll(mappedTracks.map { it.mangaId })
			addAll(mappedLogs.map { it.mangaId })
		}
		val favouriteEntityIds = mappedFavourites
			.asSequence()
			.filter { it.deletedAt == 0L }
			.mapTo(HashSet()) { it.entityId }
		val mappedBindings = snapshot.entityGraph.bindings
			.mapNotNull { binding ->
				val localExternalId = if (binding.isLocalContentBinding()) {
					val originalId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
					contentIdMap[originalId]?.toString() ?: binding.externalId
				} else {
					binding.externalId
				}
				SyncEntityBindingRecord(
					entityId = entityIdMap[binding.entityId] ?: binding.entityId,
					source = binding.source,
					externalId = localExternalId,
					confidence = binding.confidence,
					sourceKind = binding.sourceKind,
					state = binding.state,
					createdBy = binding.createdBy,
					isPrimary = binding.isPrimary,
					updatedAt = binding.updatedAt,
				)
			}
			.groupBy { BindingKey(it.source, it.externalId) }
			.values
			.map(::mergeBindings)
		val preferredFavouriteBindings = mappedBindings
			.asSequence()
			.filter { it.isLocalContentBinding() && it.entityId in favouriteEntityIds }
			.groupBy { it.entityId }
			.mapValues { (_, bindings) -> bindings.maxWith(localBindingComparator) }
		val compactBindings = mappedBindings
			.filter { binding ->
				if (!binding.isLocalContentBinding()) {
					true
				} else {
					val contentId = binding.externalId.toLongOrNull()
					contentId != null &&
						contentId in contentIds &&
						(contentId in referencedContentIds || preferredFavouriteBindings[binding.entityId] == binding)
				}
			}
			.sortedWith(compareBy<SyncEntityBindingRecord> { it.source }.thenBy { it.externalId })
		val localBindingContentIds = compactBindings
			.asSequence()
			.filter { it.isLocalContentBinding() }
			.mapNotNullTo(HashSet()) { it.externalId.toLongOrNull() }
		val finalContentIds = referencedContentIds + localBindingContentIds
		val finalContent = compactContent.filter { it.id in finalContentIds }
		val compactRelations = snapshot.entityGraph.relations
			.map { relation ->
				SyncEntityRelationRecord(
					fromEntityId = entityIdMap[relation.fromEntityId] ?: relation.fromEntityId,
					toEntityId = entityIdMap[relation.toEntityId] ?: relation.toEntityId,
					type = relation.type,
					createdAt = relation.createdAt,
				)
			}
			.filter { it.fromEntityId != it.toEntityId }
			.distinctBy { RelationKey(it.fromEntityId, it.toEntityId, it.type) }
			.sortedWith(compareBy<SyncEntityRelationRecord> { it.fromEntityId }.thenBy { it.toEntityId }.thenBy { it.type })
		return GoogleDriveSyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = snapshot.deviceId,
			syncedAt = snapshot.syncedAt,
			entityGraph = SyncEntityGraph(
				entities = compactEntities,
				bindings = compactBindings,
				relations = compactRelations,
				prefs = mappedPrefs,
			),
			content = finalContent,
			work = SyncWorkState(
				categories = snapshot.work.categories
					.groupBy { it.id }
					.values
					.map(::mergeCategories)
					.sortedBy { it.sortKey },
				history = mappedHistory,
				favourites = mappedFavourites,
				stats = mappedStats,
			),
			feed = SyncFeedState(
				tracks = mappedTracks,
				logs = mappedLogs,
			),
			config = snapshot.config,
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
				.groupBy { WorkAnchorKey(it.anchorMangaId) }
				.values
				.map(::mergeWorkHistory)
				.sortedByDescending { it.updatedAt },
			favourites = (local.favourites + remote.favourites)
				.groupBy { favourite -> WorkFavouriteKey(favourite.anchorMangaId ?: favourite.entityId, favourite.categoryId) }
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
		val newest = items.maxBy { it.updatedAt }
		val anchorMangaId = newest.anchorMangaId
			?: items
				.asSequence()
				.filter { it.anchorMangaId != null }
				.maxByOrNull { it.updatedAt }
				?.anchorMangaId
		return SyncWorkFavourite(
			entityId = newest.entityId,
			categoryId = newest.categoryId,
			anchorMangaId = anchorMangaId,
			sortKey = newest.sortKey,
			isPinned = newest.isPinned,
			createdAt = newest.createdAt,
			updatedAt = newest.updatedAt,
			deletedAt = newest.deletedAt,
		)
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

	private fun SyncTrack.withResolvedOwnerId(): SyncTrack {
		val resolvedOwnerId = resolveTrackOwnerId(entityId, mangaId).takeIf { it != 0L } ?: ownerId
		return if (ownerId == resolvedOwnerId) {
			this
		} else {
			SyncTrack(
				ownerId = resolvedOwnerId,
				mangaId = mangaId,
				entityId = entityId,
				lastChapterId = lastChapterId,
				newChapters = newChapters,
				lastCheckTime = lastCheckTime,
				lastChapterDate = lastChapterDate,
				lastResult = lastResult,
				lastError = lastError,
			)
		}
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

	private data class BindingKey(val source: String, val externalId: String)
	private data class RelationKey(val fromEntityId: Long, val toEntityId: Long, val type: String)
	private data class WorkAnchorKey(val anchorMangaId: Long)
	private data class WorkFavouriteKey(val workId: Long, val categoryId: Long)
	private data class WorkStatsKey(val entityId: Long, val startedAt: Long)

	private fun contentKey(content: SyncContent): String {
		val publicUrl = content.publicUrl.trim()
		if (publicUrl.isNotEmpty()) {
			return "public:$publicUrl"
		}
		val source = content.source.trim()
		val url = content.url.trim()
		if (url.isNotEmpty()) {
			return "source-url:$source\n$url"
		}
		return "fallback:$source\n${content.title.trim()}\n${content.coverUrl.trim()}"
	}

	private fun SyncEntityBindingRecord.isLocalContentBinding(): Boolean {
		return source == LOCAL_MANGA_SOURCE || source == LEGACY_LOCAL_MANGA_SOURCE
	}

	private fun buildEntityGroups(
		snapshot: GoogleDriveSyncSnapshot,
		contentIdMap: Map<Long, Long>,
	): EntityDisjointSet {
		val groups = EntityDisjointSet(snapshot.entityGraph.entities.map { it.id })
		snapshot.entityGraph.bindings
			.mapNotNull { binding ->
				val contentId = binding.externalId
					.toLongOrNull()
					?.takeIf { binding.isLocalContentBinding() }
					?: return@mapNotNull null
				(contentIdMap[contentId] ?: contentId) to binding.entityId
			}
			.groupBy({ it.first }, { it.second })
			.values
			.forEach { entityIds -> groups.unionAll(entityIds) }
		return groups
	}

	private class EntityDisjointSet(entityIds: List<Long>) {

		private val parent = entityIds.associateWithTo(LinkedHashMap()) { it }

		fun find(entityId: Long): Long {
			val current = parent[entityId] ?: return entityId
			if (current == entityId) {
				return entityId
			}
			val root = find(current)
			parent[entityId] = root
			return root
		}

		fun unionAll(entityIds: List<Long>) {
			val ids = entityIds.distinct()
			if (ids.size < 2) {
				return
			}
			val canonical = ids.min()
			ids.forEach { union(canonical, it) }
		}

		private fun union(left: Long, right: Long) {
			val leftRoot = find(left)
			val rightRoot = find(right)
			if (leftRoot == rightRoot) {
				return
			}
			val canonical = minOf(leftRoot, rightRoot)
			val duplicate = maxOf(leftRoot, rightRoot)
			parent[duplicate] = canonical
		}
	}

	private val localBindingComparator = compareBy<SyncEntityBindingRecord>(
		{ it.source == LOCAL_MANGA_SOURCE },
		{ it.isPrimary },
		{ it.updatedAt },
		{ it.confidence },
	)

	private const val LOCAL_MANGA_SOURCE = "local_manga"
	private const val LEGACY_LOCAL_MANGA_SOURCE = "0"
}

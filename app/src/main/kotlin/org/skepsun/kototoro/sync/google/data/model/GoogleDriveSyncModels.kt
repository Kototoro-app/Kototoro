package org.skepsun.kototoro.sync.google.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.stats.data.WorkStatsEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.resolveTrackOwnerId

@Serializable
class GoogleDriveSyncSnapshot(
	@SerialName("schema") val schemaVersion: Int = SCHEMA_VERSION,
	@SerialName("namespace") val namespace: String = NAMESPACE_WORK_V2,
	@SerialName("semantic_schema") val semanticSchemaVersion: Int = SEMANTIC_SCHEMA_VERSION,
	@SerialName("device_id") val deviceId: String = "",
	@SerialName("synced_at") val syncedAt: Long = 0L,
	@SerialName("entity_graph") val entityGraph: SyncEntityGraph = SyncEntityGraph(),
	@SerialName("content") val content: List<SyncContent> = emptyList(),
	@SerialName("work") val work: SyncWorkState = SyncWorkState(),
	@SerialName("feed") val feed: SyncFeedState = SyncFeedState(),
	@SerialName("config") val config: SyncConfig? = null,
) {

	companion object {

		const val SCHEMA_VERSION = 1
		const val NAMESPACE_WORK_V2 = "kototoro.work.v2"
		const val SEMANTIC_SCHEMA_VERSION = 1
	}
}

@Serializable
class SyncEntityGraph(
	@SerialName("entities") val entities: List<SyncEntityRecord> = emptyList(),
	@SerialName("bindings") val bindings: List<SyncEntityBindingRecord> = emptyList(),
	@SerialName("relations") val relations: List<SyncEntityRelationRecord> = emptyList(),
	@SerialName("prefs") val prefs: List<SyncEntityPrefsRecord> = emptyList(),
)

@Serializable
class SyncContent(
	@SerialName("id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("alt_title") val altTitles: String? = null,
	@SerialName("url") val url: String,
	@SerialName("public_url") val publicUrl: String,
	@SerialName("rating") val rating: Float,
	@SerialName("nsfw") val isNsfw: Boolean,
	@SerialName("content_rating") val contentRating: String? = null,
	@SerialName("cover_url") val coverUrl: String,
	@SerialName("large_cover_url") val largeCoverUrl: String? = null,
	@SerialName("state") val state: String? = null,
	@SerialName("author") val authors: String? = null,
	@SerialName("source") val source: String,
	@SerialName("content_type") val contentType: String? = null,
) {

	constructor(entity: MangaEntity) : this(
		id = entity.id,
		title = entity.title,
		altTitles = entity.altTitles,
		url = entity.url,
		publicUrl = entity.publicUrl,
		rating = entity.rating,
		isNsfw = entity.isNsfw,
		contentRating = entity.contentRating,
		coverUrl = entity.coverUrl,
		largeCoverUrl = entity.largeCoverUrl,
		state = entity.state,
		authors = entity.authors,
		source = entity.source,
		contentType = entity.contentType,
	)

	fun toEntity(localId: Long = id): MangaEntity {
		return MangaEntity(
			id = localId,
			title = title,
			altTitles = altTitles,
			url = url,
			publicUrl = publicUrl,
			rating = rating,
			isNsfw = isNsfw,
			contentRating = contentRating,
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			state = state,
			authors = authors,
			source = source,
			contentType = contentType,
		)
	}
}

@Serializable
class SyncEntityRecord(
	@SerialName("id") val id: Long,
	@SerialName("sync_id") val syncId: String = "",
	@SerialName("type") val type: String,
	@SerialName("content_type") val contentType: String? = null,
	@SerialName("primary_name") val primaryName: String,
	@SerialName("name_hash") val nameHash: Long,
	@SerialName("aliases") val aliases: String? = null,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("last_accessed") val lastAccessed: Long,
	@SerialName("access_count") val accessCount: Int,
)

@Serializable
class SyncEntityBindingRecord(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("source") val source: String,
	@SerialName("external_id") val externalId: String,
	@SerialName("confidence") val confidence: Float = 1f,
	@SerialName("source_kind") val sourceKind: String,
	@SerialName("state") val state: String,
	@SerialName("created_by") val createdBy: String,
	@SerialName("is_primary") val isPrimary: Boolean,
	@SerialName("updated_at") val updatedAt: Long,
)

@Serializable
class SyncEntityRelationRecord(
	@SerialName("from_entity_id") val fromEntityId: Long,
	@SerialName("to_entity_id") val toEntityId: Long,
	@SerialName("type") val type: String,
	@SerialName("created_at") val createdAt: Long,
)

@Serializable
class SyncEntityPrefsRecord(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("preferred_local_manga_id") val preferredLocalMangaId: Long?,
	@SerialName("title_override") val titleOverride: String? = null,
	@SerialName("cover_override") val coverUrlOverride: String? = null,
	@SerialName("content_rating_override") val contentRatingOverride: String? = null,
	@SerialName("reading_status") val readingStatus: String? = null,
	@SerialName("metadata_source_kind") val metadataSourceKind: String? = null,
	@SerialName("metadata_binding_source") val metadataBindingSource: String?,
	@SerialName("metadata_binding_external_id") val metadataBindingExternalId: String?,
	@SerialName("metadata_source_service") val metadataSourceService: Int? = null,
	@SerialName("metadata_source_remote_id") val metadataSourceRemoteId: Long? = null,
	@SerialName("updated_at") val updatedAt: Long,
)

@Serializable
class SyncWorkState(
	@SerialName("categories") val categories: List<SyncFavouriteCategory> = emptyList(),
	@SerialName("history") val history: List<SyncWorkHistory> = emptyList(),
	@SerialName("favourites") val favourites: List<SyncWorkFavourite> = emptyList(),
	@SerialName("stats") val stats: List<SyncWorkStats> = emptyList(),
)

@Serializable
class SyncFavouriteCategory(
	@SerialName("id") val id: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String,
	@SerialName("track") val track: Boolean,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean,
	@SerialName("deleted_at") val deletedAt: Long = 0L,
) {

	constructor(entity: FavouriteCategoryEntity) : this(
		id = entity.categoryId.toLong(),
		createdAt = entity.createdAt,
		sortKey = entity.sortKey,
		title = entity.title,
		order = entity.order,
		track = entity.track,
		isVisibleInLibrary = entity.isVisibleInLibrary,
		deletedAt = entity.deletedAt,
	)

	fun toEntity(localId: Long = id): FavouriteCategoryEntity {
		return FavouriteCategoryEntity(
			categoryId = localId.toInt(),
			createdAt = createdAt,
			sortKey = sortKey,
			title = title,
			order = order,
			track = track,
			isVisibleInLibrary = isVisibleInLibrary,
			deletedAt = deletedAt,
		)
	}
}

@Serializable
class SyncWorkHistory(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("anchor_manga_id") val anchorMangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long = 0L,
	@SerialName("page") val page: Int = 0,
	@SerialName("scroll") val scroll: Float = 0f,
	@SerialName("percent") val percent: Float = 0f,
	@SerialName("chapters") val chaptersCount: Int = 0,
	@SerialName("parent_chapter_id") val parentChapterId: Long? = null,
	@SerialName("deleted_at") val deletedAt: Long = 0L,
) {

	constructor(entity: WorkHistoryEntity) : this(
		entityId = entity.entityId,
		anchorMangaId = entity.anchorMangaId,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		chapterId = entity.chapterId,
		page = entity.page,
		scroll = entity.scroll,
		percent = entity.percent,
		chaptersCount = entity.chaptersCount,
		parentChapterId = entity.parentChapterId,
		deletedAt = entity.deletedAt,
	)

	fun toEntity(localEntityId: Long, localMangaId: Long): WorkHistoryEntity {
		return WorkHistoryEntity(
			entityId = localEntityId,
			anchorMangaId = localMangaId,
			createdAt = createdAt,
			updatedAt = updatedAt,
			chapterId = chapterId,
			page = page,
			scroll = scroll,
			percent = percent,
			deletedAt = deletedAt,
			chaptersCount = chaptersCount,
			parentChapterId = parentChapterId,
		)
	}
}

@Serializable
class SyncWorkFavourite(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("anchor_manga_id") val anchorMangaId: Long? = null,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("pinned") val isPinned: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("deleted_at") val deletedAt: Long,
) {

	constructor(entity: WorkFavouriteEntity) : this(
		entityId = entity.entityId,
		categoryId = entity.categoryId,
		anchorMangaId = entity.anchorMangaId,
		sortKey = entity.sortKey,
		isPinned = entity.isPinned,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		deletedAt = entity.deletedAt,
	)

	fun toEntity(localEntityId: Long, localCategoryId: Long, localMangaId: Long?): WorkFavouriteEntity {
		return WorkFavouriteEntity(
			entityId = localEntityId,
			categoryId = localCategoryId,
			anchorMangaId = localMangaId,
			sortKey = sortKey,
			isPinned = isPinned,
			createdAt = createdAt,
			deletedAt = deletedAt,
			updatedAt = updatedAt,
		)
	}
}

@Serializable
class SyncWorkStats(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("anchor_manga_id") val anchorMangaId: Long,
	@SerialName("started_at") val startedAt: Long,
	@SerialName("duration") val duration: Long,
	@SerialName("pages") val pages: Int,
) {

	constructor(entity: WorkStatsEntity) : this(
		entityId = entity.entityId,
		anchorMangaId = entity.anchorMangaId,
		startedAt = entity.startedAt,
		duration = entity.duration,
		pages = entity.pages,
	)

	fun toEntity(localEntityId: Long, localMangaId: Long): WorkStatsEntity {
		return WorkStatsEntity(
			entityId = localEntityId,
			anchorMangaId = localMangaId,
			startedAt = startedAt,
			duration = duration,
			pages = pages,
		)
	}
}

@Serializable
class SyncFeedState(
	@SerialName("tracks") val tracks: List<SyncTrack> = emptyList(),
	@SerialName("logs") val logs: List<SyncTrackLog> = emptyList(),
)

@Serializable
class SyncTrack(
	@SerialName("owner_id") val ownerId: Long,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("entity_id") val entityId: Long? = null,
	@SerialName("last_chapter_id") val lastChapterId: Long,
	@SerialName("chapters_new") val newChapters: Int,
	@SerialName("last_check_time") val lastCheckTime: Long,
	@SerialName("last_chapter_date") val lastChapterDate: Long,
	@SerialName("last_result") val lastResult: Int = TrackEntity.RESULT_NONE,
	@SerialName("last_error") val lastError: String? = null,
) {

	constructor(entity: TrackEntity) : this(
		ownerId = entity.ownerId,
		mangaId = entity.mangaId,
		entityId = entity.entityId,
		lastChapterId = entity.lastChapterId,
		newChapters = entity.newChapters,
		lastCheckTime = entity.lastCheckTime,
		lastChapterDate = entity.lastChapterDate,
		lastResult = entity.lastResult,
		lastError = entity.lastError,
	)

	fun toEntity(): TrackEntity {
		return TrackEntity(
			ownerId = resolveTrackOwnerId(entityId, mangaId).takeIf { it != 0L } ?: ownerId,
			mangaId = mangaId,
			entityId = entityId,
			lastChapterId = lastChapterId,
			newChapters = newChapters.coerceAtLeast(0),
			lastCheckTime = lastCheckTime.coerceAtLeast(0L),
			lastChapterDate = lastChapterDate.coerceAtLeast(0L),
			lastResult = lastResult,
			lastError = lastError,
		)
	}
}

@Serializable
class SyncTrackLog(
	@SerialName("owner_id") val ownerId: Long,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("entity_id") val entityId: Long? = null,
	@SerialName("chapters") val chapters: String,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("unread") val isUnread: Boolean,
) {

	constructor(entity: TrackLogEntity) : this(
		ownerId = entity.ownerId,
		mangaId = entity.mangaId,
		entityId = entity.entityId,
		chapters = entity.chapters,
		createdAt = entity.createdAt,
		isUnread = entity.isUnread,
	)

	fun toEntity(): TrackLogEntity {
		return TrackLogEntity(
			ownerId = resolveTrackOwnerId(entityId, mangaId).takeIf { it != 0L } ?: ownerId,
			mangaId = mangaId,
			entityId = entityId,
			chapters = chapters,
			createdAt = createdAt.coerceAtLeast(0L),
			isUnread = isUnread,
		)
	}
}

@Serializable
class SyncConfig(
	@SerialName("revision") val revision: Long = 0L,
	@SerialName("settings") val settings: Map<String, String> = emptyMap(),
)

package org.skepsun.kototoro.sync.google.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity

@Serializable
class GoogleDriveSyncSnapshot(
    @SerialName("schema") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("namespace") val namespace: String = NAMESPACE_CONTENT_V3,
    @SerialName("semantic_schema") val semanticSchemaVersion: Int = SEMANTIC_SCHEMA_VERSION,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("synced_at") val syncedAt: Long = 0L,
    @SerialName("content") val content: List<SyncContent> = emptyList(),
    @SerialName("categories") val categories: List<SyncFavouriteCategory> = emptyList(),
    @SerialName("history") val history: List<SyncHistory> = emptyList(),
    @SerialName("favourites") val favourites: List<SyncFavourite> = emptyList(),
    @SerialName("stats") val stats: List<SyncStats> = emptyList(),
    @SerialName("feed") val feed: SyncFeedState = SyncFeedState(),
    @SerialName("config") val config: SyncConfig? = null,
    @SerialName("repositories") val repositories: List<SyncExtensionRepo> = emptyList(),
    @SerialName("source_states") val sourceStates: List<SyncSourceState> = emptyList(),
    @SerialName("json_sources") val jsonSources: List<SyncJsonSource> = emptyList(),
    @SerialName("extensions") val extensions: List<SyncExtensionPackage> = emptyList(),
    // Legacy fields for backward compatibility with kototoro.work.v2 snapshots
    @SerialName("entity_graph") val entityGraph: SyncEntityGraph = SyncEntityGraph(),
    @SerialName("work") val work: SyncWorkState = SyncWorkState(),
) {

    fun normalizeToContentV3(): GoogleDriveSyncSnapshot {
        if (namespace == NAMESPACE_CONTENT_V3) {
            return this
        }
        val normalizedCategories = if (categories.isNotEmpty()) categories else work.categories
        val normalizedHistory = if (history.isNotEmpty()) history else {
            work.history.mapNotNull {
                if (it.anchorMangaId > 0) {
                    SyncHistory(
                        mangaId = it.anchorMangaId,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        chapterId = it.chapterId,
                        page = it.page,
                        scroll = it.scroll,
                        percent = it.percent,
                        chaptersCount = it.chaptersCount,
                        parentChapterId = it.parentChapterId,
                        deletedAt = it.deletedAt,
                    )
                } else null
            }
        }
        val normalizedFavourites = if (favourites.isNotEmpty()) favourites else {
            work.favourites.mapNotNull {
                val anchorId = it.anchorMangaId
                if (anchorId != null && anchorId > 0) {
                    SyncFavourite(
                        mangaId = anchorId,
                        categoryId = it.categoryId,
                        sortKey = it.sortKey,
                        isPinned = it.isPinned,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                } else null
            }
        }
        val normalizedStats = if (stats.isNotEmpty()) stats else {
            work.stats.mapNotNull {
                if (it.anchorMangaId > 0) {
                    SyncStats(
                        mangaId = it.anchorMangaId,
                        startedAt = it.startedAt,
                        duration = it.duration,
                        pages = it.pages,
                    )
                } else null
            }
        }
        return GoogleDriveSyncSnapshot(
            schemaVersion = SCHEMA_VERSION,
            namespace = NAMESPACE_CONTENT_V3,
            semanticSchemaVersion = SEMANTIC_SCHEMA_VERSION,
            deviceId = deviceId,
            syncedAt = syncedAt,
            content = content,
            categories = normalizedCategories,
            history = normalizedHistory,
            favourites = normalizedFavourites,
            stats = normalizedStats,
            feed = feed,
            config = config,
            repositories = repositories,
            sourceStates = sourceStates,
            jsonSources = jsonSources,
            extensions = extensions,
        )
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val NAMESPACE_CONTENT_V3 = "kototoro.content.v3"
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
class SyncHistory(
    @SerialName("manga_id") val mangaId: Long,
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
    constructor(entity: HistoryEntity) : this(
        mangaId = entity.mangaId,
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

    fun toEntity(targetMangaId: Long = mangaId) = HistoryEntity(
        mangaId = targetMangaId,
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

@Serializable
class SyncFavourite(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("sort_key") val sortKey: Int = 0,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("deleted_at") val deletedAt: Long = 0L,
) {
    constructor(entity: FavouriteEntity) : this(
        mangaId = entity.mangaId,
        categoryId = entity.categoryId,
        sortKey = entity.sortKey,
        isPinned = entity.isPinned,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    fun toEntity(targetMangaId: Long = mangaId, targetCategoryId: Long = categoryId) = FavouriteEntity(
        mangaId = targetMangaId,
        categoryId = targetCategoryId,
        sortKey = sortKey,
        isPinned = isPinned,
        createdAt = createdAt,
        deletedAt = deletedAt,
        updatedAt = maxOf(updatedAt, createdAt),
    )
}

@Serializable
class SyncStats(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("pages") val pages: Int,
) {
    constructor(entity: StatsEntity) : this(
        mangaId = entity.mangaId,
        startedAt = entity.startedAt,
        duration = entity.duration,
        pages = entity.pages,
    )

    fun toEntity(targetMangaId: Long = mangaId) = StatsEntity(
        mangaId = targetMangaId,
        startedAt = startedAt,
        duration = duration,
        pages = pages,
    )
}

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
    @SerialName("entity_id") val entityId: Long = 0L,
    @SerialName("anchor_manga_id") val anchorMangaId: Long = 0L,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("chapter_id") val chapterId: Long = 0L,
    @SerialName("page") val page: Int = 0,
    @SerialName("scroll") val scroll: Float = 0f,
    @SerialName("percent") val percent: Float = 0f,
    @SerialName("chapters") val chaptersCount: Int = 0,
    @SerialName("parent_chapter_id") val parentChapterId: Long? = null,
    @SerialName("deleted_at") val deletedAt: Long = 0L,
)

@Serializable
class SyncWorkFavourite(
    @SerialName("entity_id") val entityId: Long = 0L,
    @SerialName("category_id") val categoryId: Long = 0L,
    @SerialName("anchor_manga_id") val anchorMangaId: Long? = null,
    @SerialName("sort_key") val sortKey: Int = 0,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("deleted_at") val deletedAt: Long = 0L,
)

@Serializable
class SyncWorkStats(
    @SerialName("entity_id") val entityId: Long = 0L,
    @SerialName("anchor_manga_id") val anchorMangaId: Long = 0L,
    @SerialName("started_at") val startedAt: Long = 0L,
    @SerialName("duration") val duration: Long = 0L,
    @SerialName("pages") val pages: Int = 0,
)

@Serializable
class SyncFeedState(
    @SerialName("tracks") val tracks: List<SyncTrack> = emptyList(),
    @SerialName("logs") val logs: List<SyncTrackLog> = emptyList(),
)

@Serializable
class SyncTrack(
    @SerialName("owner_id") val ownerId: Long = 0L,
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("entity_id") val entityId: Long? = null,
    @SerialName("last_chapter_id") val lastChapterId: Long = 0L,
    @SerialName("chapters_new") val newChapters: Int = 0,
    @SerialName("last_check_time") val lastCheckTime: Long = 0L,
    @SerialName("last_chapter_date") val lastChapterDate: Long = 0L,
    @SerialName("last_result") val lastResult: Int = TrackEntity.RESULT_NONE,
    @SerialName("last_error") val lastError: String? = null,
) {

    constructor(entity: TrackEntity) : this(
        ownerId = entity.ownerId,
        mangaId = entity.mangaId,
        entityId = null,
        lastChapterId = entity.lastChapterId,
        newChapters = entity.newChapters,
        lastCheckTime = entity.lastCheckTime,
        lastChapterDate = entity.lastChapterDate,
        lastResult = entity.lastResult,
        lastError = entity.lastError,
    )

    fun toEntity(targetMangaId: Long = mangaId): TrackEntity {
        return TrackEntity(
            mangaId = targetMangaId,
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
    @SerialName("owner_id") val ownerId: Long = 0L,
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("entity_id") val entityId: Long? = null,
    @SerialName("chapters") val chapters: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("unread") val isUnread: Boolean,
) {

    constructor(entity: TrackLogEntity) : this(
        ownerId = entity.ownerId,
        mangaId = entity.mangaId,
        entityId = null,
        chapters = entity.chapters,
        createdAt = entity.createdAt,
        isUnread = entity.isUnread,
    )

    fun toEntity(targetMangaId: Long = mangaId): TrackLogEntity {
        return TrackLogEntity(
            mangaId = targetMangaId,
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

const val MAX_SYNC_PACKAGE_SIZE_BYTES = 5 * 1024 * 1024L // 5MB

@Serializable
class SyncExtensionRepo(
    @SerialName("type") val type: ExternalExtensionType,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("name") val name: String,
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("website") val website: String = "",
    @SerialName("signing_key_fingerprint") val signingKeyFingerprint: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("last_success_at") val lastSuccessAt: Long = 0L,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("version") val version: String? = null,
) {
    constructor(entity: ExternalExtensionRepoEntity) : this(
        type = entity.type,
        baseUrl = entity.baseUrl,
        name = entity.name,
        shortName = entity.shortName,
        website = entity.website,
        signingKeyFingerprint = entity.signingKeyFingerprint,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        lastSuccessAt = entity.lastSuccessAt,
        lastError = entity.lastError,
        version = entity.version,
    )

    fun toEntity() = ExternalExtensionRepoEntity(
        type = type,
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSuccessAt = lastSuccessAt,
        lastError = lastError,
        version = version,
    )
}

@Serializable
class SyncSourceState(
    @SerialName("source") val source: String,
    @SerialName("enabled") val isEnabled: Boolean,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("sort_key") val sortKey: Int = 0,
    @SerialName("used_at") val usedAt: Long = 0L,
) {
    constructor(entity: MangaSourceEntity) : this(
        source = entity.source,
        isEnabled = entity.isEnabled,
        isPinned = entity.isPinned,
        sortKey = entity.sortKey,
        usedAt = entity.lastUsedAt,
    )
}

@Serializable
class SyncJsonSource(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String,
    @SerialName("type") val type: JsonSourceType,
    @SerialName("config") val config: String,
    @SerialName("enabled") val isEnabled: Boolean = true,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("last_used_at") val lastUsedAt: Long = 0L,
    @SerialName("icon_url") val iconUrl: String? = null,
) {
    constructor(entity: JsonSourceEntity) : this(
        id = entity.id,
        name = entity.name,
        type = entity.type,
        config = entity.config,
        isEnabled = entity.enabled,
        isPinned = entity.isPinned,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        lastUsedAt = entity.lastUsedAt,
        iconUrl = entity.iconUrl,
    )

    fun toEntity(localId: String = id.ifBlank { name }) = JsonSourceEntity(
        id = localId,
        name = name,
        type = type,
        config = config,
        enabled = isEnabled,
        createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
        updatedAt = if (updatedAt > 0L) updatedAt else System.currentTimeMillis(),
        lastUsedAt = lastUsedAt,
        isPinned = isPinned,
        iconUrl = iconUrl,
    )
}

@Serializable
class SyncExtensionPackage(
    @SerialName("package_id") val packageId: String,
    @SerialName("name") val name: String,
    @SerialName("kind") val kind: String,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("version_code") val versionCode: Long? = null,
    @SerialName("repo_url") val repoUrl: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("payload_base64") val payloadBase64: String? = null,
    @SerialName("is_payload_included") val isPayloadIncluded: Boolean = false,
)

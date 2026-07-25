package org.skepsun.kototoro.backups.external

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsagiBackupIndex(
    @SerialName("app_id") val appId: String = "org.draken.usagi",
    @SerialName("app_version") val appVersion: Int = 1,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class UsagiCategoryBackup(
    @SerialName("category_id") val categoryId: Int,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("sort_key") val sortKey: Int,
    @SerialName("title") val title: String,
    @SerialName("order") val order: String = "NEWEST",
    @SerialName("track") val track: Boolean = true,
    @SerialName("show_in_lib") val isVisibleInLibrary: Boolean = true,
)

@Serializable
data class UsagiTagBackup(
    @SerialName("id") val id: Long,
    @SerialName("title") val title: String,
    @SerialName("key") val key: String,
    @SerialName("source") val source: String,
    @SerialName("pinned") val isPinned: Boolean = false,
)

@Serializable
data class UsagiMangaBackup(
    @SerialName("id") val id: Long,
    @SerialName("title") val title: String,
    @SerialName("alt_title") val altTitles: String? = null,
    @SerialName("url") val url: String,
    @SerialName("public_url") val publicUrl: String,
    @SerialName("rating") val rating: Float = -1f,
    @SerialName("nsfw") val isNsfw: Boolean = false,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("cover_url") val coverUrl: String,
    @SerialName("large_cover_url") val largeCoverUrl: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("author") val authors: String? = null,
    @SerialName("source") val source: String,
    @SerialName("tags") val tags: Set<UsagiTagBackup> = emptySet(),
)

@Serializable
data class UsagiFavouriteBackup(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("sort_key") val sortKey: Int = 0,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("manga") val manga: UsagiMangaBackup,
)

@Serializable
data class UsagiHistoryBackup(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("chapter_id") val chapterId: Long,
    @SerialName("page") val page: Int,
    @SerialName("scroll") val scroll: Float,
    @SerialName("percent") val percent: Float = 0f,
    @SerialName("chapters") val chaptersCount: Int = 0,
    @SerialName("manga") val manga: UsagiMangaBackup,
)

@Serializable
data class UsagiBookmarkBackup(
    @SerialName("manga") val manga: UsagiMangaBackup,
    @SerialName("tags") val tags: Set<UsagiTagBackup>,
    @SerialName("bookmarks") val bookmarks: List<UsagiBookmarkItem>,
)

@Serializable
data class UsagiBookmarkItem(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("page_id") val pageId: Long,
    @SerialName("chapter_id") val chapterId: Long,
    @SerialName("page") val page: Int,
    @SerialName("scroll") val scroll: Int,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("percent") val percent: Float,
)

@Serializable
data class UsagiSourceBackup(
    @SerialName("source") val source: String,
    @SerialName("sort_key") val sortKey: Int = 0,
    @SerialName("used_at") val lastUsedAt: Long = System.currentTimeMillis(),
    @SerialName("added_in") val addedIn: Int = 1,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("enabled") val isEnabled: Boolean = true,
)

@Serializable
data class UsagiScrobblingBackup(
    @SerialName("scrobbler") val scrobbler: Int,
    @SerialName("id") val id: Int,
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("target_id") val targetId: Long,
    @SerialName("status") val status: String?,
    @SerialName("chapter") val chapter: Int,
    @SerialName("comment") val comment: String?,
    @SerialName("rating") val rating: Float,
)

@Serializable
data class UsagiStatisticBackup(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("pages") val pages: Int,
)

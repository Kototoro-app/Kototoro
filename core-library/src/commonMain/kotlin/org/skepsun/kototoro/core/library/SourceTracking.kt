package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.tracking.domain.confirmManualTrackingLink

/** Binding and display authority use the same rows and IDs as Android; raw source records stay intact. */
class SourceTracking(private val database: MangaDatabase) {
    suspend fun selected(contentId: Long): TrackingSiteItemEntity? = selected(listOf(contentId))[contentId]

    suspend fun selected(contentIds: List<Long>): Map<Long, TrackingSiteItemEntity> {
        if (contentIds.isEmpty()) return emptyMap()
        val prefs = contentIds.distinct().chunked(500).flatMap { database.getPreferencesDao().findByIds(it) }
            .filter { it.metadataSourceKind == "tracking" }
        val items = prefs.groupBy { it.metadataSourceService }.flatMap { (service, entries) ->
            if (service == null) emptyList() else database.getTrackingSiteDao()
                .findItems(service, entries.mapNotNull { it.metadataSourceRemoteId })
        }.associateBy { it.service to it.remoteId }
        return prefs.mapNotNull { pref ->
            items[pref.metadataSourceService to pref.metadataSourceRemoteId]?.let { pref.mangaId to it }
        }.toMap()
    }

    suspend fun bindings(contentId: Long): List<TrackingSiteItemEntity> =
        database.getTrackingSiteDao().findLinksByManga(contentId).mapNotNull {
            database.getTrackingSiteDao().findItem(it.service, it.remoteId)
        }

    suspend fun bind(content: SourceContent, item: TrackingSiteItemEntity, useMetadata: Boolean = true) =
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                requireStoredIdentity(content)
                require(item.service > 0 && item.remoteId > 0 && item.title.isNotBlank())
                requireTrackingCatalogIdentity(database.getTrackingSiteDao().findItem(item.service, item.remoteId), item)
                database.getTrackingSiteDao().upsertItem(item)
                database.getTrackingSiteDao().confirmManualTrackingLink(item.service, item.remoteId, content.id,
                    content.source.name, item.updatedAt)
                if (useMetadata) selectInTransaction(content.id, item) else {
                    val prefs = database.getPreferencesDao().find(content.id)
                    if (prefs?.metadataSourceKind == "tracking" && prefs.metadataSourceService == item.service &&
                        prefs.metadataSourceRemoteId != item.remoteId) selectInTransaction(content.id, null)
                }
            }
        }

    suspend fun select(content: SourceContent, service: Int?, remoteId: Long?) =
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                requireStoredIdentity(content)
                val item = if (service == null && remoteId == null) null else {
                    require(service != null && remoteId != null)
                    require(database.getTrackingSiteDao().findLinksByManga(service, content.id)
                        .any { it.remoteId == remoteId }) { "作品尚未绑定此追踪条目" }
                    database.getTrackingSiteDao().findItem(service, remoteId) ?: error("缺少追踪条目缓存")
                }
                selectInTransaction(content.id, item)
            }
        }

    suspend fun unbind(content: SourceContent, service: Int) = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            requireStoredIdentity(content)
            database.getTrackingSiteDao().deleteLinksByManga(service, content.id)
            val prefs = database.getPreferencesDao().find(content.id)
            if (prefs?.metadataSourceKind == "tracking" && prefs.metadataSourceService == service) {
                selectInTransaction(content.id, null)
            }
        }
    }

    private suspend fun requireStoredIdentity(content: SourceContent) {
        val stored = database.getMangaDao().find(content.id)?.manga ?: error("请先保存作品详情")
        require(stored.source == content.source.name && stored.url == content.url) { "作品身份不一致" }
    }

    private suspend fun selectInTransaction(id: Long, item: TrackingSiteItemEntity?) {
        val dao = database.getPreferencesDao()
        val prefs = dao.find(id) ?: MangaPrefsEntity(
            mangaId = id,
            mode = -1,
            cfBrightness = 0f,
            cfContrast = 0f,
            cfInvert = false,
            cfGrayscale = false,
            cfBookEffect = false,
            titleOverride = null,
            coverUrlOverride = null,
            contentRatingOverride = null,
            metadataSourceKind = null,
            metadataSourceService = null,
            metadataSourceRemoteId = null,
            readingStatus = null,
            ignoredTrackingSuggestionService = null,
            ignoredTrackingSuggestionRemoteId = null,
        )
        dao.upsert(prefs.copy(metadataSourceKind = if (item == null) "base" else "tracking",
            metadataSourceService = item?.service, metadataSourceRemoteId = item?.remoteId))
    }
}

/** The existing cache key is (service, ID); some providers allocate IDs separately for anime and manga. */
fun requireTrackingCatalogIdentity(cached: TrackingSiteItemEntity?, incoming: TrackingSiteItemEntity) {
    fun kind(item: TrackingSiteItemEntity?): String? {
        if (item?.service !in listOf(1, 3, 4)) return null
        val path = item?.siteUrl.orEmpty()
        return when {
            Regex("/animes?/").containsMatchIn(path) -> "anime"
            Regex("/mangas?/").containsMatchIn(path) -> "manga"
            else -> null
        }
    }
    val previous = kind(cached)
    val next = kind(incoming)
    require(previous == null || next == null || previous == next) {
        "此网站的动画与漫画使用了相同编号，当前无法同时关联这两个条目；已有资料已保留"
    }
}

/** Presentation only: IDs, source, URLs, opaque fields and chapters always belong to the source. */
fun SourceContent.withTrackingMetadata(item: TrackingSiteItemEntity?): SourceContent {
    if (item == null) return this
    val cover = item.coverUrl?.takeIf { it.isNotBlank() }
    return copy(title = item.title.takeIf { it.isNotBlank() } ?: title,
        altTitles = decodeTrackingStrings(item.altTitles).toSet().ifEmpty { altTitles },
        coverUrl = cover ?: coverUrl, largeCoverUrl = cover ?: largeCoverUrl,
        description = item.summary?.takeIf { it.isNotBlank() } ?: description,
        rating = item.rating?.let { value -> item.scoreMax?.takeIf { it > 0 }?.let { value / it } } ?: rating,
        authors = decodeTrackingStrings(item.authors).toSet().ifEmpty { authors },
        tags = decodeTrackingStrings(item.tags).map { SourceTag(it, it, source) }.toSet().ifEmpty { tags })
}

fun encodeTrackingStrings(values: List<String>): String? = values.takeIf { it.isNotEmpty() }
    ?.let { JsonArray(it.map(::JsonPrimitive)).toString() }

fun decodeTrackingStrings(raw: String?): List<String> = raw?.let {
    runCatching { Json.parseToJsonElement(it).jsonArray.mapNotNull { value ->
        (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    } }.getOrNull()
}.orEmpty()

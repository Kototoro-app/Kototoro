package org.skepsun.kototoro.backups.external

import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.containsAdultTagKeyword
import org.skepsun.kototoro.core.model.isAdultTagKeyword
import org.skepsun.kototoro.core.model.isExplicitlySafeTagKeyword
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Pure planning logic for the external backup bulk import. Everything here is memory-only so
 * it can be unit tested without a database; the repository only orchestrates the actual bulk
 * writes around it. Identity is the persisted manga row (projection-first): records that
 * resolve to the same manga id are merged, nothing else is deduplicated.
 */

/**
 * One resolvable backup record prepared for the bulk write pass. All DB-independent values
 * (identity, tags, manga row) are computed in memory. Duplicate records that resolve to the
 * same [mangaId] are merged with union semantics (favourite flags OR-ed, categories united,
 * newest history kept), which is strictly more faithful than per-record last-write-wins.
 */
internal class BulkImportEntry(
    initialRecord: ExternalBackupContentRecord,
    val mangaId: Long,
) {
    var record: ExternalBackupContentRecord = initialRecord
        private set

    var title: String = initialRecord.title.trim()
        .ifBlank { initialRecord.url.trim() }
        .ifBlank { initialRecord.publicUrl.trim() }
        private set

    var tags: List<TagEntity> = buildTags(initialRecord)
        private set

    var mangaEntity: MangaEntity = buildMangaEntity()
        private set

    fun mergeFrom(other: ExternalBackupContentRecord) {
        val keepHistory = (other.historyTimestamp ?: 0L) > (record.historyTimestamp ?: 0L)
        val historyRecord = if (keepHistory) other else record
        record = record.copy(
            isFavorite = record.isFavorite || other.isFavorite,
            favoriteTimestamp = listOfNotNull(record.favoriteTimestamp, other.favoriteTimestamp)
                .filter { it > 0L }
                .minOrNull(),
            favoriteCategoryOrders = (record.favoriteCategoryOrders + other.favoriteCategoryOrders).distinct(),
            historyTimestamp = historyRecord.historyTimestamp,
            historyChapterUrl = historyRecord.historyChapterUrl,
            progressPercent = historyRecord.progressPercent,
            chaptersCount = maxOf(record.chaptersCount, other.chaptersCount),
            readEntriesCount = maxOf(record.readEntriesCount, other.readEntriesCount),
            tags = (record.tags + other.tags).distinct(),
            coverUrl = record.coverUrl ?: other.coverUrl,
            authors = record.authors ?: other.authors,
            description = record.description ?: other.description,
        )
        title = record.title.trim()
            .ifBlank { record.url.trim() }
            .ifBlank { record.publicUrl.trim() }
        tags = buildTags(record)
        mangaEntity = buildMangaEntity()
    }

    private fun buildTags(record: ExternalBackupContentRecord): List<TagEntity> {
        return record.tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { tag ->
                TagEntity(
                    id = "${record.sourceName}|tag|${tag.lowercase()}".hashCode().toLong() and Long.MAX_VALUE,
                    title = tag,
                    key = tag.lowercase().replace(" ", "_"),
                    source = record.sourceName,
                    isPinned = false,
                )
            }
    }

    private fun buildMangaEntity(): MangaEntity {
        val isExplicitlySafe = record.tags.any { it.isExplicitlySafeTagKeyword() }
        val isAdultContentType = record.contentType in setOf(
            ContentType.HENTAI_MANGA,
            ContentType.HENTAI_NOVEL,
            ContentType.HENTAI_VIDEO,
        )
        val isAdultSource = ContentSource(record.sourceName).isNsfw()
        val hasAdultTag = record.tags.any { it.isAdultTagKeyword() || it.containsAdultTagKeyword() }

        val isNsfw = !isExplicitlySafe && (isAdultContentType || isAdultSource || hasAdultTag)
        val contentRating = when {
            isNsfw -> ContentRating.ADULT.name
            isExplicitlySafe -> ContentRating.SAFE.name
            else -> null
        }

        return MangaEntity(
            id = mangaId,
            title = title,
            altTitles = null,
            url = record.url,
            publicUrl = record.publicUrl.ifBlank { record.url },
            rating = -1f,
            isNsfw = isNsfw,
            contentRating = contentRating,
            coverUrl = record.coverUrl.orEmpty(),
            largeCoverUrl = record.coverUrl,
            state = null,
            authors = record.authors,
            source = record.sourceName,
            contentType = record.contentType.name,
        )
    }
}

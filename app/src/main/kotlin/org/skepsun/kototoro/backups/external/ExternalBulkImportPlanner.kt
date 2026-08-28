package org.skepsun.kototoro.backups.external

import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.data.computeProjectionSyncId
import org.skepsun.kototoro.entitygraph.data.hasSameNormalizedEntityName
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.util.longHashCode

/**
 * Pure planning logic for the external backup bulk import (phase 1). Everything here is
 * memory-only so it can be unit tested without a database; the repository only orchestrates
 * the actual bulk writes around it.
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

    /** Resolved WORK entity id after [planWorkEntityAssignment]; valid only afterwards. */
    var entityId: Long = 0L

    /** True when a fresh provisional entity must be inserted for this entry. */
    var isNewEntity: Boolean = false

    /** The provisional entity to insert (aligned-order input for the batch insert). */
    var newEntityRecord: EntityRecord? = null

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

    private fun buildMangaEntity(): MangaEntity = MangaEntity(
        id = mangaId,
        title = title,
        altTitles = null,
        url = record.url,
        publicUrl = record.publicUrl.ifBlank { record.url },
        rating = -1f,
        isNsfw = false,
        contentRating = null,
        coverUrl = record.coverUrl.orEmpty(),
        largeCoverUrl = record.coverUrl,
        state = null,
        authors = record.authors,
        source = record.sourceName,
        contentType = record.contentType.name,
    )
}

/**
 * Assigns every entry to a WORK entity, in memory:
 *  - Exact normalized-name match with a pre-existing entity -> attach to it (same identity
 *    evidence rule as `EntityGraphRepository.createEntity`).
 *  - Otherwise a fresh provisional entity; within-batch (nameHash, contentType) slot
 *    collisions fall back to a deterministic salted hash, mirroring createEntity's
 *    collision path. Cross-source / near-name merging is deferred to phase 2
 *    (EntityConsolidationWorker).
 *
 * Populates [BulkImportEntry.entityId], [BulkImportEntry.isNewEntity] and
 * [BulkImportEntry.newEntityRecord]. The returned list holds the provisional entities
 * in stable order, ready for the batch insert.
 */
internal fun planWorkEntityAssignment(
    entries: List<BulkImportEntry>,
    existingEntitiesByHash: Map<Long, List<EntityRecord>>,
    now: Long,
    takenSlots: MutableSet<String> = HashSet(),
    localBindingByMangaId: Map<Long, Long> = emptyMap(),
): List<EntityRecord> {
    val newEntities = ArrayList<EntityRecord>()
    for (entry in entries) {
        // Highest precedence: a manga id that already carries a local binding belongs to
        // that entity — re-anchoring it elsewhere would steal the (source, external_id)
        // primary key from its current owner (the old per-record path checked
        // findEntityByLocalMangaId first, this mirrors that).
        val boundEntityId = localBindingByMangaId[entry.mangaId]
        if (boundEntityId != null) {
            entry.entityId = boundEntityId
            entry.isNewEntity = false
            entry.newEntityRecord = null
            continue
        }
        val contentTypeName = entry.record.contentType.name
        val baseHash = computeNameHash(entry.title)
        val attachTarget = existingEntitiesByHash[baseHash].orEmpty().firstOrNull { candidate ->
            candidate.contentType == contentTypeName &&
                hasSameNormalizedEntityName(candidate.primaryName, entry.title)
        }
        if (attachTarget != null) {
            entry.entityId = attachTarget.id
            entry.isNewEntity = false
            entry.newEntityRecord = null
            continue
        }
        val slotKey = "$baseHash\u0000$contentTypeName"
        val nameHash = if (slotKey in takenSlots) {
            "$baseHash|${entry.title}|${entry.mangaId}".longHashCode()
        } else {
            baseHash
        }
        takenSlots += slotKey
        val projectionKey = ProjectionIdentityKeys.bindingKey(
            url = entry.record.url,
            publicUrl = entry.record.publicUrl,
        )
        val entityRecord = EntityRecord(
            type = EntityType.WORK.name,
            contentType = contentTypeName,
            syncId = if (projectionKey != null) {
                computeProjectionSyncId(entry.record.sourceName, projectionKey)
            } else {
                java.util.UUID.randomUUID().toString()
            },
            primaryName = entry.title,
            nameHash = nameHash,
            aliases = null,
            createdAt = now,
            lastAccessed = now,
            accessCount = 1,
        )
        entry.entityId = 0L
        entry.isNewEntity = true
        entry.newEntityRecord = entityRecord
        newEntities += entityRecord
    }
    return newEntities
}

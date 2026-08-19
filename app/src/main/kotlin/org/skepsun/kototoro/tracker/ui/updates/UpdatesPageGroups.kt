package org.skepsun.kototoro.tracker.ui.updates

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import java.time.Instant

internal data class UpdatesPagingParams(
	val filters: Set<ListFilterOption>,
	val grouped: Boolean,
	val mode: ListMode,
	val groupTab: BrowseGroupTab,
	val sourceTags: Set<SourceTag>,
)

/**
 * Inserts a [ListHeader] before each date bucket change across page boundaries.
 * Calls to [headerFor] must be cheap and idempotent; the incidental per-group
 * header is remembered in the ViewModel while pages are mapped, so separators
 * never re-derive per-page headers (which would duplicate headers for a date
 * that spans two pages).
 */
internal fun PagingData<ListModel>.applyUpdatesPagingPresentation(
	grouped: Boolean,
	headerFor: (ListModel) -> ListHeader?,
): PagingData<ListModel> {
	if (!grouped) {
		return this
	}
	return insertSeparators { before: ListModel?, after: ListModel? ->
		val beforeHeader = before?.let(headerFor)
		val afterHeader = after?.let(headerFor)
		afterHeader?.takeIf { before == null || beforeHeader != afterHeader }
	}
}

internal data class UpdateGroupKey(
	val uiId: Long,
	val contentTypeOrdinal: Int,
)

internal data class UpdateGroup(
	val uiId: Long,
	val representative: ContentTracking,
	val mangaIds: Set<Long>,
	val lastChapterDate: Instant?,
	val totalNewChapters: Int,
	val entityId: Long?,
	val preferredLocalMangaId: Long?,
	val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
)

/**
 * Groups one paged batch of [ContentTracking] rows by entity. Pure: all entity
 * lookups (preferred local projection, metadata selection) are resolved once per
 * batch upstream and folded in, so the paging path never falls back to
 * per-entity N+1 resolves.
 */
internal fun List<ContentTracking>.groupTrackingByEntity(
	preferredLocalIdsByEntity: Map<Long, Long?>,
	metadataSelectionsByEntity: Map<Long, ContentDataRepository.MetadataSourceSelection?>,
): List<UpdateGroup> {
	if (isEmpty()) {
		return emptyList()
	}
	val displayTypeOrdinalByEntity = groupBy(ContentTracking::entityId)
		.mapNotNull { (entityId, items) ->
			entityId?.let { it to items.resolveDisplayContentTypeOrdinal() }
		}
		.toMap()
	val grouped = LinkedHashMap<UpdateGroupKey, MutableList<ContentTracking>>(size)
	for (item in this) {
		val entityId = item.entityId
		val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get)
			?: item.manga.source.contentType.ordinal
		val key = UpdateGroupKey(
			uiId = entityId?.toUiGroupId(contentTypeOrdinal) ?: item.manga.id,
			contentTypeOrdinal = contentTypeOrdinal,
		)
		grouped.getOrPut(key) { ArrayList(1) }.add(item)
	}
	return grouped.map { (key, items) ->
		items.toUpdateGroup(
			uiId = key.uiId,
			entityId = items.firstNotNullOfOrNull(ContentTracking::entityId),
			preferredLocalMangaId = items.firstNotNullOfOrNull { item ->
				item.entityId?.let(preferredLocalIdsByEntity::get) ?: item.preferredLocalMangaId
			},
			metadataSourceSelection = items.firstNotNullOfOrNull { item ->
				item.entityId?.let(metadataSelectionsByEntity::get)
			},
		)
	}
}

private fun List<ContentTracking>.toUpdateGroup(
	uiId: Long,
	entityId: Long?,
	preferredLocalMangaId: Long?,
	metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
): UpdateGroup {
	val representative = firstOrNull { it.manga.id == preferredLocalMangaId } ?: maxWithOrNull(
		compareBy<ContentTracking>(
			{ it.lastChapterDate ?: Instant.EPOCH },
			{ it.lastCheck ?: Instant.EPOCH },
			{ it.newChapters },
		),
	) ?: first()
	return UpdateGroup(
		uiId = uiId,
		representative = representative,
		mangaIds = mapTo(LinkedHashSet(size)) { it.manga.id },
		lastChapterDate = mapNotNull { it.lastChapterDate }.maxOrNull(),
		totalNewChapters = sumOf { it.newChapters },
		entityId = entityId,
		preferredLocalMangaId = preferredLocalMangaId ?: representative.manga.id,
		metadataSourceSelection = metadataSourceSelection,
	)
}

private fun List<ContentTracking>.resolveDisplayContentTypeOrdinal(): Int {
	return firstOrNull { !it.manga.source.name.startsWith("TRACKING_") }?.manga?.source?.contentType?.ordinal
		?: first().manga.source.contentType.ordinal
}

internal fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

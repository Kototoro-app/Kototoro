package org.skepsun.kototoro.work.domain

import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

data class WorkStatsSummary(
    val totalPages: Int = 0,
    val averageTimePerPage: Long = 0L,
    val entryCount: Int = 0,
)

data class WorkTrackingSummary(
    val anchorMangaId: Long,
    val lastChapterId: Long,
    val newChapters: Int,
    val lastCheckTime: Long,
    val lastChapterDate: Long,
)

data class WorkAggregate(
    val identity: WorkIdentity,
    val displayProjection: Content?,
    val projections: List<Content>,
    val categories: Set<FavouriteCategory> = emptySet(),
    val history: WorkHistoryEntity? = null,
    val favourite: WorkFavouriteEntity? = null,
    val stats: WorkStatsSummary? = null,
    val tracking: WorkTrackingSummary? = null,
    val contentType: ContentType? = null,
)

internal fun WorkAggregate.matchesFavouriteMacroFilter(
    option: ListFilterOption.Macro,
    brokenProjectionSourceNames: Set<String> = emptySet(),
): Boolean = when (option) {
    ListFilterOption.Macro.COMPLETED -> history?.percent?.let(ReadingProgress::isCompleted) == true
    ListFilterOption.Macro.NEW_CHAPTERS -> (tracking?.newChapters ?: 0) > 0
    ListFilterOption.Macro.MULTI_PROJECTION -> identity.localMangaIds.size > 1
    ListFilterOption.Macro.BROKEN_PROJECTION -> projections
        .ifEmpty { listOfNotNull(displayProjection) }
        .any { it.source.name in brokenProjectionSourceNames }
    else -> true
}

internal fun Content.matchesPublicationStateFilters(filterOptions: Set<ListFilterOption>): Boolean {
    val selectedStates = filterOptions.asSequence()
        .filterIsInstance<ListFilterOption.PublicationState>()
        .map(ListFilterOption.PublicationState::state)
        .toSet()
    return selectedStates.isEmpty() || state in selectedStates
}

internal fun WorkAggregate.resolveReadingStatus(explicitStatus: ScrobblingStatus?): ScrobblingStatus =
    explicitStatus ?: when {
        history == null -> ScrobblingStatus.PLANNED
        ReadingProgress.isCompleted(history.percent) -> ScrobblingStatus.COMPLETED
        else -> ScrobblingStatus.READING
    }

internal fun ScrobblingStatus.matchesReadingStatusFilters(filterOptions: Set<ListFilterOption>): Boolean {
    val selectedStatuses = filterOptions.asSequence()
        .filterIsInstance<ListFilterOption.ReadingStatus>()
        .map(ListFilterOption.ReadingStatus::status)
        .toSet()
    return selectedStatuses.isEmpty() || this in selectedStatuses
}

internal fun WorkAggregate.matchesTagAndSourceFilters(filterOptions: Set<ListFilterOption>): Boolean {
    val selectedTags = filterOptions.asSequence()
        .filterIsInstance<ListFilterOption.Tag>()
        .map(ListFilterOption.Tag::tag)
        .toSet()
    val selectedSourceNames = filterOptions.asSequence()
        .filterIsInstance<ListFilterOption.Source>()
        .map { it.mangaSource.name }
        .toSet()
    if (selectedTags.isEmpty() && selectedSourceNames.isEmpty()) {
        return true
    }
    val candidates = projections.ifEmpty { listOfNotNull(displayProjection) }
    val matchesTag = selectedTags.isEmpty() || candidates.any { content ->
        content.tags.any(selectedTags::contains)
    }
    val matchesSource = selectedSourceNames.isEmpty() || candidates.any { content ->
        content.source.name in selectedSourceNames
    }
    return matchesTag && matchesSource
}

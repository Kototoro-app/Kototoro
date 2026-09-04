package org.skepsun.kototoro.history.domain.library

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps derived [HistoryCardEntry] rows onto the list models the history page
 * renders (history-updates-feed komikku-alignment plan, Phase H3).
 *
 * Same discipline as the favourites/updates card mappers: display-only
 * [Content] stub, manual override first with the tracking-site metadata
 * authority as fallback, the ui id as the list item id, and the history
 * progress of the display projection. The counter stays the tracking badge
 * ([ContentListMapper]'s COUNTER option) the paging path resolved.
 */
@Singleton
class HistoryCardMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentListMapper: ContentListMapper,
) {

    @Immutable
    data class Slice(
        val mode: ListMode,
        val progressMode: ProgressIndicatorMode,
    )

    fun map(rows: List<HistoryCardEntry>, slice: Slice): List<ContentListModel> {
        if (rows.isEmpty()) {
            return emptyList()
        }
        val brokenTitle = context.getString(R.string.favourites_broken_projection_title)
        val tagTint = contentListMapper::tagTint
        val shouldComputeGroupSuffix = slice.mode == ListMode.LIST || slice.mode == ListMode.DETAILED_LIST
        val sourceLabels = if (shouldComputeGroupSuffix) HashMap<String, String>(8) else null
        return rows.map { row ->
            buildHistoryCardModel(
                HistoryCardModelRequest(
                    row = row,
                    mode = slice.mode,
                    progressMode = slice.progressMode,
                    groupSuffix = if (shouldComputeGroupSuffix && sourceLabels != null) groupSuffixOf(row, sourceLabels) else null,
                    brokenTitle = brokenTitle,
                    tagTint = tagTint,
                ),
            )
        }
    }

    /**
     * The "Current projection: X · N projections · N records" suffix of the
     * list rows (plural string included when the entity has several records).
     */
    private fun groupSuffixOf(row: HistoryCardEntry, labelCache: MutableMap<String, String>): String? {
        val sourceTitle = labelCache.getOrPut(row.sourceName) {
            ContentSource(row.sourceName).getTitle(context)
        }
        val currentProjectionLabel = if (row.localMangaIds.size > 1) {
            context.getString(
                R.string.favourites_entity_current_projection_with_count,
                sourceTitle,
                row.localMangaIds.size,
            )
        } else {
            context.getString(R.string.favourites_entity_current_projection, sourceTitle)
        }
        if (row.localMangaIds.size <= 1) {
            return currentProjectionLabel
        }
        val recordsLabel = context.resources.getQuantityString(
            R.plurals.history_grouped_records,
            row.localMangaIds.size,
            row.localMangaIds.size,
        )
        return listOf(currentProjectionLabel, recordsLabel).joinToString(" · ")
    }
}

/** Pure mapping inputs: everything [buildHistoryCardModel] needs, Android resolved. */
@Immutable
data class HistoryCardModelRequest(
    val row: HistoryCardEntry,
    val mode: ListMode,
    val progressMode: ProgressIndicatorMode,
    val groupSuffix: String?,
    val brokenTitle: String,
    val tagTint: (String) -> Int = { 0 },
)

/** Pure row -> card model projection (unit-tested without Android). */
internal fun buildHistoryCardModel(request: HistoryCardModelRequest): ContentListModel {
    val row = request.row
    val manga = row.toStubContent(request.brokenTitle)
    val override = row.toDisplayOverride()
    val trackingService = row.metadataTrackingService?.let { id ->
        ScrobblerService.entries.firstOrNull { it.id == id }
    }
    val progress = ReadingProgress(
        percent = row.percent,
        totalChapters = row.chaptersCount,
        mode = request.progressMode,
    ).takeIf { it.isValid() }
    return when (request.mode) {
        ListMode.GRID, ListMode.COMPACT_GRID -> ContentGridModel(
            manga = manga,
            override = override,
            subtitle = row.altTitle?.takeIf { it.isNotBlank() },
            counter = 0,
            projectionCount = row.localMangaIds.size,
            id = row.uiId,
            progress = progress,
            isFavorite = false,
            isSaved = false,
            isPinned = row.isPinned,
            metadataTrackingService = trackingService,
        )

        ListMode.LIST -> ContentCompactListModel(
            manga = manga,
            override = override,
            subtitle = joinSubtitles(
                row.tags.joinToString(", ") { it.title }.ifBlank { null },
                request.groupSuffix,
            ),
            counter = 0,
            projectionCount = row.localMangaIds.size,
            id = row.uiId,
            progress = progress,
            isPinned = row.isPinned,
            metadataTrackingService = trackingService,
        )

        ListMode.DETAILED_LIST -> ContentDetailedListModel(
            manga = manga,
            override = override,
            subtitle = joinSubtitles(row.altTitle?.takeIf { it.isNotBlank() }, request.groupSuffix),
            counter = 0,
            projectionCount = row.localMangaIds.size,
            id = row.uiId,
            progress = progress,
            isFavorite = false,
            isSaved = false,
            tags = row.tags.map {
                ChipModel(
                    title = it.title,
                    tint = request.tagTint(it.title),
                    data = null,
                )
            },
            isPinned = row.isPinned,
            metadataTrackingService = trackingService,
        )
    }
}

/** Manual override first, tracking-site metadata authority as fallback — field by field. */
private fun HistoryCardEntry.toDisplayOverride(): ContentOverride? {
    val merged = ContentOverride(
        coverUrl = overrideCoverUrl?.takeIf { it.isNotBlank() } ?: metadataTrackingCoverUrl,
        title = overrideTitle?.takeIf { it.isNotBlank() } ?: metadataTrackingTitle,
        contentRating = null,
    )
    return if (merged.title == null && merged.coverUrl == null) null else merged
}

private fun joinSubtitles(base: String?, suffix: String?): String? =
    listOfNotNull(base?.takeIf { it.isNotBlank() }, suffix?.takeIf { it.isNotBlank() })
        .ifEmpty { null }
        ?.joinToString(" · ")

/** Display-only [Content]: cards read identity/title/cover/state/tags off it. */
private fun HistoryCardEntry.toStubContent(brokenTitle: String): Content {
    val source = ContentSource(sourceName)
    return Content(
        id = displayMangaId ?: anchorMangaId,
        title = title.ifBlank { brokenTitle },
        altTitles = setOfNotNull(altTitle?.takeIf { it.isNotBlank() }),
        url = "",
        publicUrl = "",
        rating = rating,
        contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE,
        coverUrl = coverUrl,
        tags = tags.mapTo(LinkedHashSet()) { ContentTag(title = it.title, key = it.key, source = source) },
        state = publicationState,
        authors = setOfNotNull(author?.takeIf { it.isNotBlank() }),
        largeCoverUrl = largeCoverUrl,
        description = null,
        chapters = null,
        source = source,
        sourceData = null,
    )
}

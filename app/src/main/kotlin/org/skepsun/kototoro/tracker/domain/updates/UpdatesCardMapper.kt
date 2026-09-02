package org.skepsun.kototoro.tracker.domain.updates

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps derived [UpdateGroupRow]s onto the list models the updates page renders
 * (history-updates-feed komikku-alignment plan, Phase U4).
 *
 * Same discipline as `FavouritesCardMapper`: display-only [Content] stub (no
 * description / chapters / sourceData), the manual override first and the
 * tracking-site metadata authority as fallback, counter = the group's new
 * chapter count, and the group ui id as the list item id (that is what
 * selection and "clear updates" key on).
 */
@Singleton
class UpdatesCardMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentListMapper: ContentListMapper,
) {

    @Immutable
    data class Slice(
        val mode: ListMode,
    )

    fun map(groups: List<UpdateGroupRow>, slice: Slice): List<ContentListModel> {
        if (groups.isEmpty()) {
            return emptyList()
        }
        val brokenTitle = context.getString(R.string.favourites_broken_projection_title)
        val tagTint = contentListMapper::tagTint
        val sourceLabels = HashMap<String, String>(8)
        return groups.map { group ->
            buildUpdateCardModel(
                UpdateCardModelRequest(
                    group = group,
                    mode = slice.mode,
                    groupSuffix = groupSuffixOf(group, sourceLabels),
                    brokenTitle = brokenTitle,
                    tagTint = tagTint,
                ),
            )
        }
    }

    /** Localized source title of the representative projection, cached per mapping batch. */
    private fun groupSuffixOf(group: UpdateGroupRow, labelCache: MutableMap<String, String>): String {
        val sourceTitle = labelCache.getOrPut(group.sourceName) {
            ContentSource(group.sourceName).getTitle(context)
        }
        return if (group.mangaIds.size > 1) {
            context.getString(
                R.string.favourites_entity_current_projection_with_count,
                sourceTitle,
                group.mangaIds.size,
            )
        } else {
            context.getString(R.string.favourites_entity_current_projection, sourceTitle)
        }
    }
}

/** Pure mapping inputs: everything [buildUpdateCardModel] needs, Android resolved. */
@Immutable
data class UpdateCardModelRequest(
    val group: UpdateGroupRow,
    val mode: ListMode,
    val groupSuffix: String?,
    val brokenTitle: String,
    val tagTint: (String) -> Int = { 0 },
)

/** Pure group -> card model projection (unit-tested without Android). */
internal fun buildUpdateCardModel(request: UpdateCardModelRequest): ContentListModel {
    val group = request.group
    val manga = group.toStubContent(request.brokenTitle)
    val override = group.toDisplayOverride()
    val trackingService = group.metadataTrackingService?.let { id ->
        ScrobblerService.entries.firstOrNull { it.id == id }
    }
    return when (request.mode) {
        ListMode.GRID, ListMode.COMPACT_GRID -> ContentGridModel(
            manga = manga,
            override = override,
            subtitle = group.altTitle?.takeIf { it.isNotBlank() },
            counter = group.totalNewChapters,
            projectionCount = group.mangaIds.size,
            id = group.uiId,
            progress = null,
            isFavorite = false,
            isSaved = false,
            isPinned = group.isPinned,
            metadataTrackingService = trackingService,
        )

        ListMode.LIST -> ContentCompactListModel(
            manga = manga,
            override = override,
            subtitle = joinSubtitles(
                group.tags.joinToString(", ") { it.title }.ifBlank { null },
                request.groupSuffix,
            ),
            counter = group.totalNewChapters,
            projectionCount = group.mangaIds.size,
            id = group.uiId,
            progress = null,
            isPinned = group.isPinned,
            metadataTrackingService = trackingService,
        )

        ListMode.DETAILED_LIST -> ContentDetailedListModel(
            manga = manga,
            override = override,
            subtitle = joinSubtitles(group.altTitle?.takeIf { it.isNotBlank() }, request.groupSuffix),
            counter = group.totalNewChapters,
            projectionCount = group.mangaIds.size,
            id = group.uiId,
            progress = null,
            isFavorite = false,
            isSaved = false,
            tags = group.tags.map {
                ChipModel(
                    title = it.title,
                    tint = request.tagTint(it.title),
                    data = null,
                )
            },
            isPinned = group.isPinned,
            metadataTrackingService = trackingService,
        )
    }
}

/** Manual override first, tracking-site metadata authority as fallback — field by field. */
private fun UpdateGroupRow.toDisplayOverride(): ContentOverride? {
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
private fun UpdateGroupRow.toStubContent(brokenTitle: String): Content {
    val source = ContentSource(sourceName)
    return Content(
        id = displayMangaId ?: mangaIds.firstOrNull() ?: uiId,
        title = title.ifBlank { brokenTitle },
        altTitles = setOfNotNull(altTitle?.takeIf { it.isNotBlank() }),
        url = "",
        publicUrl = "",
        rating = rating,
        contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE,
        coverUrl = coverUrl,
        tags = tags.mapTo(LinkedHashSet()) {
            ContentTag(title = it.title, key = it.tagId.toString(), source = source)
        },
        state = publicationState,
        authors = setOfNotNull(author?.takeIf { it.isNotBlank() }),
        largeCoverUrl = null,
        description = null,
        chapters = null,
        source = source,
        sourceData = null,
    )
}

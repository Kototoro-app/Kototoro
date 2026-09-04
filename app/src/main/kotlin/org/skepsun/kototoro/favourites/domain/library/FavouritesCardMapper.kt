package org.skepsun.kototoro.favourites.domain.library

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.model.ContentSource as createContentSource
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.prefs.AppSettings
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

/**
 * Maps favourites library snapshot rows onto the shared content-list card models
 * (favourites-komikku-alignment Phase 5).
 *
 * The [Content] a card carries is a display **stub** built from the narrow row: no
 * description, no `sourceData`, no chapters, empty urls. Actions that need a real
 * projection resolve it on demand by entity id ([FavouriteContentResolver]) instead of
 * keeping a wide domain object in every row. Item identity is the entity id, so a
 * representative/cover/title change never moves a card.
 *
 * Card fields follow the legacy aggregate mapping (see `FavouriteCardFieldContractTest`):
 * `counter` is the tracked new-chapter count (zero once reading is complete), `progress`
 * comes from the work history, `projectionCount` is binding-based, `isSaved` is the
 * download flag and `isPinned` is the *membership* flag of the mapped slice. Grid and
 * compact/detailed differ only in the subtitle: grid shows the alt title, the list rows
 * show the tag line plus the "current projection" suffix.
 *
 * The display metadata authority survives the narrow row: an entity whose metadata
 * authority is a tracking site draws its cached title/cover (the legacy
 * `ContentListMapper.resolveDisplayOverride` merge — the manual override wins field by
 * field) plus the service badge, everything else follows the display projection.
 *
 * Deliberate deviations from the aggregate chain, both documented in the migration plan:
 * - rows without a display projection stay visible (the legacy mapper dropped them) with
 *   a placeholder title so entity organize stays reachable;
 * - the NSFW badge follows the persisted row flag (mapped to an explicit content rating
 *   on the stub) instead of re-running the tag heuristic, which is also what the NSFW
 *   quick filter matches on.
 */
@Reusable
class FavouritesCardMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val contentListMapper: ContentListMapper,
) {

    /** One mapped slice: the card rows of a category in display order. */
    @Immutable
    data class Slice(
        val mode: ListMode,
        val pinnedEntityIds: Set<Long> = emptySet(),
    )

    fun map(rows: List<FavouriteCardRow>, slice: Slice): List<ContentListModel> {
        if (rows.isEmpty()) {
            return emptyList()
        }
        val progressMode = settings.progressIndicatorMode
        val brokenTitle = context.getString(R.string.favourites_broken_projection_title)
        // Tag tint depends on the title alone, and a detailed list repeats the same few
        // thousand titles over every card carrying them (a 6.6k-favourite library has 113k
        // tag relations against 9.7k distinct tags), so it resolves once per title per batch.
        val tintByTitle = HashMap<String, Int>(64)
        val tagTint: (String) -> Int = { title -> tintByTitle.getOrPut(title) { contentListMapper.tagTint(title) } }
        val projectionLabels = HashMap<String, String>(8)
        // Only the list modes put the projection suffix on a card. Formatting it for a grid
        // was one localized string per card that no model ever reads (65ms of a 263ms batch).
        val needsGroupSuffix = slice.mode == ListMode.LIST || slice.mode == ListMode.DETAILED_LIST
        val sourcesByName = HashMap<String, ContentSource>(8)
        val sourceResolver: (String) -> ContentSource = { name -> sourcesByName.getOrPut(name) { createContentSource(name) } }
        return rows.map { row ->
            buildFavouriteCardModel(
                FavouriteCardModelRequest(
                    row = row,
                    mode = slice.mode,
                    progressMode = progressMode,
                    isPinned = row.entityId in slice.pinnedEntityIds,
                    groupSuffix = if (needsGroupSuffix) groupSuffixOf(row, projectionLabels) else null,
                    brokenTitle = brokenTitle,
                    tagTint = tagTint,
                    sourceResolver = sourceResolver,
                ),
            )
        }
    }

    /** Localized source title of the display projection, cached per mapping batch. */
    private fun groupSuffixOf(row: FavouriteCardRow, labelCache: MutableMap<String, String>): String {
        val sourceTitle = labelCache.getOrPut(row.sourceName) {
            createContentSource(row.sourceName).getTitle(context)
        }
        return if (row.projectionCount > 1) {
            context.getString(R.string.favourites_entity_current_projection_with_count, sourceTitle, row.projectionCount)
        } else {
            context.getString(R.string.favourites_entity_current_projection, sourceTitle)
        }
    }
}

/**
 * Pure mapping inputs: everything [buildFavouriteCardModel] needs with the Android and
 * I/O parts already resolved.
 */
@Immutable
data class FavouriteCardModelRequest(
    val row: FavouriteCardRow,
    val mode: ListMode,
    val progressMode: ProgressIndicatorMode,
    val isPinned: Boolean,
    val groupSuffix: String?,
    val brokenTitle: String,
    val tagTint: (String) -> Int = { 0 },
    val sourceResolver: (String) -> ContentSource = { createContentSource(it) },
)

/** Pure row -> card model projection, unit-tested without Android (see `FavouritesCardMapperTest`). */
internal fun buildFavouriteCardModel(request: FavouriteCardModelRequest): ContentListModel {
    val row = request.row
    val manga = row.toStubContent(request.brokenTitle, request.mode, request.sourceResolver)
    val override = row.toDisplayOverride()
    val trackingService = row.metadataTrackingService?.let { id ->
        ScrobblerService.entries.firstOrNull { it.id == id }
    }
    val progress = row.toReadingProgress(request.progressMode)
    val counter = if (progress?.isCompleted() == true) 0 else row.newChapters
    return when (request.mode) {
        ListMode.GRID, ListMode.COMPACT_GRID -> ContentGridModel(
            manga = manga,
            override = override,
            subtitle = row.altTitle?.takeIf { it.isNotBlank() },
            counter = counter,
            projectionCount = row.projectionCount,
            id = row.entityId,
            progress = progress,
            isFavorite = false,
            isSaved = row.isDownloaded,
            isPinned = request.isPinned,
            metadataTrackingService = trackingService,
        )

        ListMode.LIST -> ContentCompactListModel(
            manga = manga,
            override = override,
            subtitle = joinSubtitles(
                row.displayTags.joinToString(", ") { it.title }.ifBlank { null },
                request.groupSuffix,
            ),
            counter = counter,
            projectionCount = row.projectionCount,
            id = row.entityId,
            progress = progress,
            isPinned = request.isPinned,
            metadataTrackingService = trackingService,
        )

        ListMode.DETAILED_LIST -> ContentDetailedListModel(
            manga = manga,
            override = override,
            subtitle = joinSubtitles(row.altTitle?.takeIf { it.isNotBlank() }, request.groupSuffix),
            counter = counter,
            projectionCount = row.projectionCount,
            id = row.entityId,
            progress = progress,
            isFavorite = false,
            isSaved = row.isDownloaded,
            tags = row.displayTags.map {
                ChipModel(
                    title = it.title,
                    tint = request.tagTint(it.title),
                    data = null,
                )
            },
            isPinned = request.isPinned,
            metadataTrackingService = trackingService,
        )
    }
}

/**
 * Card override: the entity-level manual override first, the tracking site that owns the
 * display metadata authority as fallback — field by field, exactly like
 * `ContentListMapper.resolveDisplayOverride` did for the aggregate chain.
 */
private fun FavouriteCardRow.toDisplayOverride(): ContentOverride? {
    val merged = ContentOverride(
        coverUrl = overrideCoverUrl?.takeIf { it.isNotBlank() } ?: metadataTrackingCoverUrl,
        title = overrideTitle?.takeIf { it.isNotBlank() } ?: metadataTrackingTitle,
        contentRating = null,
    )
    return if (merged.title == null && merged.coverUrl == null) null else merged
}

/** The "Current projection: X · N projections" suffix of the list rows. */
private fun joinSubtitles(base: String?, suffix: String?): String? =
    listOfNotNull(base?.takeIf { it.isNotBlank() }, suffix?.takeIf { it.isNotBlank() })
        .ifEmpty { null }
        ?.joinToString(" · ")

/** [ReadingProgress] from the work-history columns of the row (null when never read). */
private fun FavouriteCardRow.toReadingProgress(mode: ProgressIndicatorMode): ReadingProgress? {
    val percent = progressPercent ?: return null
    val fixedPercent = if (ReadingProgress.isCompleted(percent)) 1f else percent
    return ReadingProgress(
        percent = fixedPercent,
        totalChapters = progressTotalChapters ?: 0,
        mode = mode,
    ).takeIf { it.isValid() }
}

/**
 * Display-only [Content]: the cards read title / cover / authors / state / tags /
 * source-name and the NSFW flag from it, everything else stays in the database.
 */
private fun FavouriteCardRow.toStubContent(
    brokenTitle: String,
    mode: ListMode,
    sourceResolver: (String) -> ContentSource,
): Content {
    val source = sourceResolver(sourceName)
    val altTitlesSet = if (altTitle.isNullOrBlank()) emptySet() else setOf(altTitle)
    val authorsSet = if (author.isNullOrBlank()) emptySet() else setOf(author)
    val tagsSet = when {
        displayTags.isEmpty() -> emptySet()
        mode == ListMode.GRID || mode == ListMode.COMPACT_GRID -> {
            val count = minOf(displayTags.size, 3)
            val result = LinkedHashSet<ContentTag>(count)
            for (i in 0 until count) {
                val tag = displayTags[i]
                result.add(ContentTag(title = tag.title, key = tag.tagId.toString(), source = source))
            }
            result
        }
        else -> emptySet()
    }
    return Content(
        id = displayMangaId ?: entityId,
        title = title.ifBlank { brokenTitle },
        altTitles = altTitlesSet,
        url = "",
        publicUrl = "",
        rating = rating,
        // Explicit rating so the badge follows the persisted flag instead of the tag heuristic.
        contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE,
        coverUrl = coverUrl,
        tags = tagsSet,
        state = publicationState,
        authors = authorsSet,
        largeCoverUrl = null,
        description = null,
        chapters = null,
        source = source,
        sourceData = null,
    )
}

package org.skepsun.kototoro.tracker.domain.feed

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import java.time.Instant
import javax.inject.Inject

/**
 * Maps feed snapshot rows onto the feed card model
 * (history-updates-feed komikku-alignment plan, Phase F4).
 *
 * The [Content] a card carries is a display **stub** built from the narrow row —
 * the same discipline as `FavouritesCardMapper`: no description, no `sourceData`,
 * no chapters, empty urls. Item identity is the log id (the synthetic showAll row
 * uses the negated manga id, exactly like the legacy `fromAllTrackedContent`).
 *
 * Card fields follow the legacy `ContentListMapper.toFeedItems` mapping:
 * `count` is the chapter-list size (or the pending count of a synthetic row),
 * `isNew` is the unread flag, the override is the manual title/cover of the
 * display manga.
 */
@Reusable
class FeedCardMapper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Mapping inputs with the Android part already resolved. */
    @Immutable
    data class Request(
        val brokenTitle: String,
    )

    fun map(rows: List<FeedCardRow>, request: Request): List<FeedItem> {
        if (rows.isEmpty()) {
            return emptyList()
        }
        return rows.map { row -> buildFeedCardModel(row, request) }
    }
}

/** Pure row -> card model projection, unit-tested without Android (see `FeedCardMapperTest`). */
internal fun buildFeedCardModel(row: FeedCardRow, request: FeedCardMapper.Request): FeedItem {
    return FeedItem(
        id = row.logId,
        entityId = row.entityId,
        preferredLocalMangaId = row.preferredLocalMangaId,
        override = row.toDisplayOverride(),
        manga = row.toStubContent(request.brokenTitle),
        createdAt = Instant.ofEpochMilli(row.createdAt),
        count = row.chapters.size,
        isNew = row.unread,
        totalChapters = row.chapters.size,
    )
}

/** Manual title/cover override of the display manga, field by field. */
private fun FeedCardRow.toDisplayOverride(): ContentOverride? {
    val merged = ContentOverride(
        coverUrl = overrideCoverUrl?.takeIf { it.isNotBlank() },
        title = overrideTitle?.takeIf { it.isNotBlank() },
        contentRating = null,
    )
    return if (merged.title == null && merged.coverUrl == null) null else merged
}

/** Display-only [Content]: title / cover / authors / state / tags / source / NSFW. */
private fun FeedCardRow.toStubContent(brokenTitle: String): Content {
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
        tags = tagTitles.mapTo(LinkedHashSet()) { ContentTag(title = it, key = it.lowercase(), source = source) },
        state = publicationState,
        authors = setOfNotNull(author?.takeIf { it.isNotBlank() }),
        largeCoverUrl = null,
        description = null,
        chapters = null,
        source = source,
        sourceData = null,
    )
}

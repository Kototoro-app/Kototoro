package org.skepsun.kototoro.favourites.domain.library

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.list.ui.model.ContentGridModel

/** Upper bound of the "continue reading" shelf: a rail, not a second grid. */
const val FAVOURITES_SHELF_LIMIT = 12

/**
 * The "continue reading" shelf above a favourites category.
 *
 * [items] are grid cards so the shelf draws with the same card as the grid below it and
 * the home rails; [totalCount] / [updatedCount] describe the whole (filtered) category.
 */
@Immutable
data class FavouritesShelfState(
    val items: List<ContentGridModel> = emptyList(),
    val totalCount: Int = 0,
    val updatedCount: Int = 0,
)

/**
 * Picks the shelf rows out of a category slice: works with new chapters first (newest
 * chapter first), then works being read (most recently read first). Finished, never-read
 * and merely pinned works are left out — pinned works already lead the grid below.
 */
fun selectFavouritesShelfRows(
    rows: List<FavouriteCardRow>,
    limit: Int = FAVOURITES_SHELF_LIMIT,
): List<FavouriteCardRow> {
    val updated = rows.filter { it.newChapters > 0 }
        .sortedByDescending { it.lastChapterDate }
    val reading = rows.filter { row ->
        row.newChapters <= 0 &&
            (row.lastReadAt ?: 0L) > 0L &&
            (row.progressPercent ?: 0f) < 1f
    }.sortedByDescending { it.lastReadAt }
    return (updated + reading).take(limit)
}

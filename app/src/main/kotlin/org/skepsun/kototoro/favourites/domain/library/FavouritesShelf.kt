package org.skepsun.kototoro.favourites.domain.library

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.list.ui.model.ContentGridModel

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

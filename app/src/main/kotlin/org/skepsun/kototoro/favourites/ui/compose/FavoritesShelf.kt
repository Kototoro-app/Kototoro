package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.favourites.domain.library.FavouritesShelfState
import org.skepsun.kototoro.list.ui.compose.KototoroContentCardGrid
import org.skepsun.kototoro.list.ui.compose.contentListSharedElementKey
import org.skepsun.kototoro.list.ui.model.ContentGridModel

/**
 * "Continue reading" rail above a favourites category: updated works first, then works in
 * progress. It draws the regular grid card at the home-rail size, so it reads as part of
 * the same library instead of a second, differently styled card family.
 */
@Composable
fun FavoritesShelf(
    state: FavouritesShelfState,
    gridScale: Float,
    compactOverlay: Boolean,
    sharedElementInstanceKey: String,
    onItemClick: (ContentGridModel, Rect?, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.items.isEmpty()) return
    val posterStyle = remember(gridScale) { compactPosterRailCardStyle(gridScale) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppLayoutTokens.screenHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.favourites_shelf_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.updatedCount > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = stringResource(R.string.favourites_shelf_updated, state.updatedCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            // Grid cells carry their own 2dp side padding; trim it off the screen edge so
            // the first cover lines up with the title above.
            contentPadding = PaddingValues(horizontal = AppLayoutTokens.screenHorizontalPadding - 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                items = state.items,
                key = { it.id },
                contentType = { "favourites_shelf_card" },
            ) { item ->
                val sharedKey = remember(item.id, sharedElementInstanceKey) {
                    contentListSharedElementKey(item, sharedElementInstanceKey)
                }
                KototoroContentCardGrid(
                    item = item,
                    gridScale = gridScale,
                    sharedElementInstanceKey = sharedElementInstanceKey,
                    cardStyle = posterStyle,
                    // Follows the list mode like the grid below and the home rails: in the
                    // compact grid the title sits on the cover, never under it.
                    compactOverlay = compactOverlay,
                    cellContentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    onClick = { bounds -> onItemClick(item, bounds, sharedKey) },
                    onLongClick = {},
                    modifier = Modifier.width(posterStyle.itemWidth),
                )
            }
        }
        // Heads the grid below, so it does not read as a continuation of the shelf.
        Text(
            text = stringResource(R.string.favourites_shelf_summary, state.totalCount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start = AppLayoutTokens.screenHorizontalPadding,
                end = AppLayoutTokens.screenHorizontalPadding,
                top = 10.dp,
            ),
        )
    }
}

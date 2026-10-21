package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.favourites.domain.orderCategoriesForPicker
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel.CategoryVisibilityState

/**
 * "Which categories does this Space show?" — a display filter, not a data change.
 *
 * Every category is listed (including the ones hidden from the shelf), checked when the
 * Space shows it. Toggling a box writes the local Space preference only, so the same
 * category can be shown in one Space and hidden in another while the category itself,
 * its members, backups and sync stay exactly as they were.
 *
 * Categories that hold favourites in this Space come first: a Space over manga sources
 * owns none of the anime categories, and burying the six useful rows under twenty empty
 * ones would hide the actual choice.
 */
@Composable
internal fun FavouriteCategoryDisplayDialog(
    state: CategoryVisibilityState,
    onShownChanged: (categoryId: Long, isShown: Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val orderedCategories = remember(state.categories, state.favouriteCounts) {
        orderCategoriesForPicker(state.categories, state.favouriteCounts)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.favourite_categories_in_space)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        text = stringResource(R.string.favourite_categories_in_space_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                items(orderedCategories, key = { it.id }) { category ->
                    CategoryShownRow(
                        category = category,
                        favouriteCount = state.favouriteCounts.getOrDefault(category.id, 0),
                        isShown = category.id !in state.hiddenCategoryIds,
                        onShownChanged = { onShownChanged(category.id, it) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = if (state.hiddenCategoryIds.isEmpty()) {
            null
        } else {
            {
                TextButton(onClick = onShowAll) {
                    Text(stringResource(R.string.show_all_categories))
                }
            }
        },
    )
}

@Composable
private fun CategoryShownRow(
    category: FavouriteCategory,
    favouriteCount: Int,
    isShown: Boolean,
    onShownChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onShownChanged(!isShown) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!category.isVisibleInLibrary) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_eye_off),
                contentDescription = stringResource(R.string.category_hidden_from_shelf),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (favouriteCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = favouriteCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Checkbox(checked = isShown, onCheckedChange = onShownChanged)
    }
}

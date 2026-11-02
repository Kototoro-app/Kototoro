package org.skepsun.kototoro.favourites.ui.categories.select.compose

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import org.skepsun.kototoro.list.ui.compose.CategoryChoice
import org.skepsun.kototoro.list.ui.compose.CategoryChoicesContent
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.util.ext.joinToStringWithLimit
import org.skepsun.kototoro.favourites.ui.categories.select.FavoriteDialogViewModel
import org.skepsun.kototoro.favourites.ui.categories.select.model.ContentCategoryItem
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content

/**
 * Compose implementation of the Favorite Categories dialog.
 * Replaces the legacy FavoriteDialog (AlertDialogFragment).
 *
 * @param contentTitle   title of the manga being categorized
 * @param allCategories  all available favourite categories
 * @param memberCategoryIds  IDs of categories that the manga currently belongs to
 * @param onCategoryToggle  callback when a category checkbox is toggled (categoryId, newChecked)
 * @param onManageCategories  callback to open the category management screen
 * @param onDismiss  callback when the dialog is dismissed
 * @param hasHiddenCategories  true when the Space hides categories the dialog does not list
 * @param onShowAllCategories  reveals the categories the Space hides, for this dialog only
 */
@Composable
fun FavoriteCategoryDialog(
    contentTitle: String,
    allCategories: List<FavouriteCategory>,
    memberCategoryIds: Set<Long>,
    onCategoryToggle: (categoryId: Long, isChecked: Boolean) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
    hasHiddenCategories: Boolean = false,
    onShowAllCategories: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = contentTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        text = {
            CategoryChoicesContent(
                choices = allCategories.map { category ->
                    CategoryChoice(category.id, category.title,
                        if (category.id in memberCategoryIds) ToggleableState.On else ToggleableState.Off,
                        tracking = category.isTrackingEnabled, hidden = !category.isVisibleInLibrary)
                },
                onToggle = onCategoryToggle,
                hasHiddenCategories = hasHiddenCategories,
                onShowAll = onShowAllCategories,
            )
        },
        confirmButton = {
            TvTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TvTextButton(onClick = {
                onDismiss()
                onManageCategories()
            }) {
                Text(stringResource(R.string.manage))
            }
        },
    )
}

@Composable
fun FavoriteCategoryDialogRoute(
    manga: Collection<Content>,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: FavoriteDialogViewModel = hiltViewModel(key = "favorite-dialog-${manga.hashCode()}"),
) {
    LaunchedEffect(manga) {
        viewModel.initialize(manga)
    }

    val content by viewModel.content.collectAsStateWithLifecycle()
    FavoriteCategoryDialog(
        contentTitle = manga.joinToStringWithLimit(LocalContext.current, 92) { it.title },
        items = content.items,
        onCategoryToggle = viewModel::setChecked,
        onManageCategories = onManageCategories,
        onDismiss = onDismiss,
        hasHiddenCategories = content.hasHiddenCategories,
        onShowAllCategories = { viewModel.setShowAllCategories(true) },
    )
}

@Composable
fun FavoriteCategoryDialog(
    contentTitle: String,
    items: List<ListModel>,
    onCategoryToggle: (categoryId: Long, isChecked: Boolean) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
    hasHiddenCategories: Boolean = false,
    onShowAllCategories: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = contentTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        text = {
            FavoriteCategoryDialogContent(items, onCategoryToggle, hasHiddenCategories, onShowAllCategories)
        },
        confirmButton = {
            TvTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TvTextButton(onClick = {
                onDismiss()
                onManageCategories()
            }) {
                Text(stringResource(R.string.manage))
            }
        },
    )
}

@Composable
private fun FavoriteCategoryDialogContent(
    items: List<ListModel>,
    onCategoryToggle: (Long, Boolean) -> Unit,
    hasHiddenCategories: Boolean,
    onShowAllCategories: () -> Unit,
) {
    val empty = items.singleOrNull() as? EmptyState
    CategoryChoicesContent(
        choices = items.filterIsInstance<ContentCategoryItem>().map { item ->
            CategoryChoice(item.category.id, item.category.title,
                when (item.checkedState) {
                    MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
                    MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                }, tracking = item.isTrackerEnabled && item.category.isTrackingEnabled,
                hidden = !item.category.isVisibleInLibrary)
        },
        onToggle = onCategoryToggle,
        loading = items.singleOrNull() == LoadingState,
        emptyMessage = empty?.textPrimaryText?.toString()
            ?: stringResource(empty?.textPrimary?.takeIf { it != 0 } ?: R.string.empty_favourite_categories),
        hasHiddenCategories = hasHiddenCategories,
        onShowAll = onShowAllCategories,
    )
}

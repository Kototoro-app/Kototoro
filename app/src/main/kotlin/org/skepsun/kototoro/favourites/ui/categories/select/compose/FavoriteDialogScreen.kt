package org.skepsun.kototoro.favourites.ui.categories.select.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.util.ext.joinToStringWithLimit
import org.skepsun.kototoro.favourites.ui.categories.select.FavoriteDuplicatePrompt
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
 */
@Composable
fun FavoriteCategoryDialog(
    contentTitle: String,
    allCategories: List<FavouriteCategory>,
    memberCategoryIds: Set<Long>,
    onCategoryToggle: (categoryId: Long, isChecked: Boolean) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
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
            if (allCategories.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty_favourite_categories),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(
                        items = allCategories,
                        key = { it.id },
                        contentType = { "category_row" },
                    ) { category ->
                        val isChecked = category.id in memberCategoryIds
                        CategoryRow(
                            category = category,
                            isChecked = isChecked,
                            onClick = { onCategoryToggle(category.id, !isChecked) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = {
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

    val items by viewModel.content.collectAsStateWithLifecycle()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsStateWithLifecycle()
    FavoriteCategoryDialog(
        contentTitle = manga.joinToStringWithLimit(LocalContext.current, 92) { it.title },
        items = items,
        onCategoryToggle = viewModel::setChecked,
        onManageCategories = onManageCategories,
        onDismiss = onDismiss,
    )
    DuplicateFavoritePromptDialog(
        prompt = duplicatePrompt,
        onConfirm = viewModel::confirmDuplicatePrompt,
        onMergeBack = viewModel::mergeBackDuplicatePrompt,
        onDismiss = viewModel::dismissDuplicatePrompt,
    )
}

@Composable
fun FavoriteCategoryDialog(
    contentTitle: String,
    items: List<ListModel>,
    onCategoryToggle: (categoryId: Long, isChecked: Boolean) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
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
            FavoriteCategoryDialogContent(
                items = items,
                onCategoryToggle = onCategoryToggle,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = {
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
    onCategoryToggle: (categoryId: Long, isChecked: Boolean) -> Unit,
) {
    if (items.size == 1) {
        when (val item = items.first()) {
            LoadingState -> {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                return
            }

            is EmptyState -> {
                Text(
                    text = item.textPrimaryText?.toString()
                        ?: stringResource(item.textPrimary.takeIf { it != 0 } ?: R.string.empty_favourite_categories),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return
            }
        }
    }

    LazyColumn(
        modifier = Modifier.heightIn(max = 360.dp),
    ) {
        items(
            items = items.filterIsInstance<ContentCategoryItem>(),
            key = { it.category.id },
            contentType = { "category_row" },
        ) { item ->
            CategoryRow(
                item = item,
                onClick = {
                    onCategoryToggle(item.category.id, item.checkedState != MaterialCheckBox.STATE_CHECKED)
                },
            )
        }
    }
}

@Composable
fun DuplicateFavoritePromptDialog(
    prompt: FavoriteDuplicatePrompt?,
    onConfirm: () -> Unit,
    onMergeBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (prompt == null) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.favourite_duplicate_candidate_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.favourite_duplicate_candidate_message,
                    prompt.contentTitle,
                    prompt.candidates.joinToString(separator = "\n") { candidate ->
                        val suffix = candidate.sourceLabels
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " (", postfix = ")")
                            .orEmpty()
                        "• ${candidate.title}$suffix"
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.continue_action))
            }
        },
        dismissButton = {
            if (prompt.mergeBackTargetEntityId != null) {
                TextButton(onClick = onMergeBack) {
                    Text(stringResource(R.string.favourite_duplicate_merge_back))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun CategoryRow(
    category: FavouriteCategory,
    isChecked: Boolean,
    onClick: () -> Unit,
) {
    CategoryRow(
        title = category.title,
        state = if (isChecked) ToggleableState.On else ToggleableState.Off,
        showTracker = category.isTrackingEnabled,
        showHidden = !category.isVisibleInLibrary,
        onClick = onClick,
    )
}

@Composable
private fun CategoryRow(
    item: ContentCategoryItem,
    onClick: () -> Unit,
) {
    CategoryRow(
        title = item.category.title,
        state = when (item.checkedState) {
            MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
            MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
            else -> ToggleableState.Off
        },
        showTracker = item.isTrackerEnabled && item.category.isTrackingEnabled,
        showHidden = !item.category.isVisibleInLibrary,
        onClick = onClick,
    )
}

@Composable
private fun CategoryRow(
    title: String,
    state: ToggleableState,
    showTracker: Boolean,
    showHidden: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = state,
            onClick = null,
        )
        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        if (showTracker) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showHidden) {
            Icon(
                painter = painterResource(R.drawable.ic_eye_off),
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

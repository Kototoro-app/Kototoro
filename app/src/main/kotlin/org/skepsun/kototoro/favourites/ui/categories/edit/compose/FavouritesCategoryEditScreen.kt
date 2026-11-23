package org.skepsun.kototoro.favourites.ui.categories.edit.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.buttons.SingleChoiceButtonRow
import org.skepsun.kototoro.core.ui.compose.KototoroSnackbarHost
import org.skepsun.kototoro.core.ui.progress.LinearProgressIndicator
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.ui.compose.listSortOptions
import org.skepsun.kototoro.settings.compose.SettingsActionPreference
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsChoicePreference
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding
import org.skepsun.kototoro.settings.compose.SettingsItemGroup
import org.skepsun.kototoro.settings.compose.SettingsPreferenceGroup
import org.skepsun.kototoro.settings.compose.SettingsSwitchPreference
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold
import org.skepsun.kototoro.settings.compose.settingsPreferenceLayout

/**
 * The category editor, laid out like the settings pages: a header card with the cover and the name, then the
 * category's options as grouped preference rows. The sort order opens the shared choice dialog (the old read-only
 * text field swallowed the tap, so its menu never opened, issue #710), and the manual order opens the reorder dialog.
 */
@Composable
internal fun FavouritesCategoryEditScreen(
    isEditing: Boolean,
    title: String,
    sortOrder: ListSortOrder,
    isTrackerAvailable: Boolean,
    isTrackerEnabled: Boolean,
    isVisibleOnShelf: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onTitleChanged: (String) -> Unit,
    onSortOrderChanged: (ListSortOrder) -> Unit,
    onTrackerChanged: (Boolean) -> Unit,
    onShelfChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    coverModel: Any? = null,
    canRemoveCover: Boolean = false,
    onPickCover: () -> Unit = {},
    onRemoveCover: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    /** Opens the manual-order dialog; null for a new category, which has no works to arrange yet. */
    onReorderClick: (() -> Unit)? = null,
) {
    // The shelf's sort menu pairs the two directions of one key (added date, title, progress, last read) into one
    // option with an ascending/descending switch; the editor offers the same choices.
    val sortGroups = remember(onReorderClick != null) {
        listSortOptions(
            ListSortOrder.FAVORITES.sortedBy { it.ordinal }
                .filter { onReorderClick != null || it != ListSortOrder.MANUAL },
        )
    }
    val selectedGroup = sortGroups.firstOrNull { it.contains(sortOrder) } ?: sortGroups.first()
    val isDescending = selectedGroup.descending == sortOrder
    val sortOptions = sortGroups.map { SettingsChoiceOption(it, stringResource(it.titleResId)) }
    val enabled = !isLoading

    SettingsTopBarScaffold(
        title = stringResource(if (isEditing) R.string.edit_category else R.string.create_category),
        onNavigateUp = onBack,
        actions = {
            Button(
                enabled = title.isNotBlank() && enabled,
                onClick = onSave,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(stringResource(R.string.save))
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = SettingsContentHorizontalPadding,
                    end = SettingsContentHorizontalPadding,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header") {
                    CategoryHeaderCard(
                        title = title,
                        onTitleChanged = onTitleChanged,
                        coverModel = coverModel,
                        canRemoveCover = canRemoveCover,
                        enabled = enabled,
                        onPickCover = onPickCover,
                        onRemoveCover = onRemoveCover,
                    )
                }
                item(key = "options") {
                    SettingsPreferenceGroup(title = "") {
                        item {
                            SettingsChoicePreference(
                                title = stringResource(R.string.sort_order),
                                value = selectedGroup,
                                options = sortOptions,
                                iconRes = R.drawable.ic_sort,
                                enabled = enabled,
                                onValueChange = { group ->
                                    // A new key keeps the direction chosen so far, where it has one.
                                    val order = group.orderFor(isDescending)
                                    onSortOrderChanged(order)
                                    // As on the shelf's sort menu, picking the manual order goes straight to arranging it.
                                    if (order == ListSortOrder.MANUAL) onReorderClick?.invoke()
                                },
                            )
                        }
                        if (selectedGroup.ascending != null && selectedGroup.descending != null) {
                            item {
                                SingleChoiceButtonRow(
                                    options = listOf(false, true),
                                    selected = isDescending,
                                    onSelected = { onSortOrderChanged(selectedGroup.orderFor(it)) },
                                    label = {
                                        stringResource(if (it) R.string.sort_order_desc else R.string.sort_order_asc)
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.settingsPreferenceLayout(enabled),
                                )
                            }
                        }
                        if (sortOrder == ListSortOrder.MANUAL && onReorderClick != null) {
                            item {
                                SettingsActionPreference(
                                    title = stringResource(R.string.reorder),
                                    iconRes = R.drawable.ic_reorder_handle,
                                    enabled = enabled,
                                    onClick = onReorderClick,
                                )
                            }
                        }
                        item {
                            SettingsSwitchPreference(
                                title = stringResource(R.string.show_on_shelf),
                                checked = isVisibleOnShelf,
                                iconRes = R.drawable.ic_eye,
                                enabled = enabled,
                                onCheckedChange = onShelfChanged,
                            )
                        }
                        if (isTrackerAvailable) {
                            item {
                                SettingsSwitchPreference(
                                    title = stringResource(R.string.check_for_new_chapters),
                                    checked = isTrackerEnabled,
                                    iconRes = R.drawable.ic_notification,
                                    enabled = enabled,
                                    onCheckedChange = onTrackerChanged,
                                )
                            }
                        }
                    }
                }
                if (errorMessage != null) {
                    item(key = "error") {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
                if (isLoading) {
                    item(key = "loading") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            snackbarHostState?.let {
                KototoroSnackbarHost(
                    hostState = it,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(WindowInsets.navigationBars.asPaddingValues()),
                )
            }
        }
    }
}

/** The cover beside the name, so the card reads like the category tile it edits. Tapping the cover picks a new one. */
@Composable
private fun CategoryHeaderCard(
    title: String,
    onTitleChanged: (String) -> Unit,
    coverModel: Any?,
    canRemoveCover: Boolean,
    enabled: Boolean,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
) {
    SettingsItemGroup(itemCount = 1) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                onClick = onPickCover,
                enabled = enabled,
                modifier = Modifier
                    .height(dimensionResource(R.dimen.category_covers_height))
                    .aspectRatio(13f / 18f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (coverModel == null) {
                        // No custom artwork: the category falls back to its automatic cover stack.
                        Icon(
                            painter = painterResource(R.drawable.ic_images),
                            contentDescription = stringResource(R.string.choose_cover),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = stringResource(R.string.choose_cover),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChanged,
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.name)) },
                    modifier = Modifier.fillMaxWidth().tvTextInputFocusEscape(),
                )
                Text(
                    text = stringResource(R.string.custom_cover_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TvTextButton(onClick = onPickCover, enabled = enabled) {
                        Text(stringResource(R.string.choose_cover))
                    }
                    if (canRemoveCover) {
                        TvTextButton(onClick = onRemoveCover, enabled = enabled) {
                            Text(stringResource(R.string.remove_custom_cover))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FavouritesCategoryEditScreenPreview() {
    KototoroTheme {
        FavouritesCategoryEditScreen(
            isEditing = false,
            title = "Favorites",
            sortOrder = ListSortOrder.NEWEST,
            isTrackerAvailable = true,
            isTrackerEnabled = false,
            isVisibleOnShelf = true,
            isLoading = false,
            errorMessage = null,
            onTitleChanged = {},
            onSortOrderChanged = {},
            onTrackerChanged = {},
            onShelfChanged = {},
            onSave = {},
            onBack = {},
        )
    }
}

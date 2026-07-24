package org.skepsun.kototoro.favourites.ui.categories.edit.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.list.domain.ListSortOrder

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val sortOrders = remember { ListSortOrder.FAVORITES.sortedBy { it.ordinal } }
    val horizontalPadding = dimensionResource(R.dimen.screen_padding)

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(stringResource(if (isEditing) R.string.edit_category else R.string.create_category)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Button(
                        enabled = title.isNotBlank() && !isLoading,
                        onClick = onSave,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChanged,
                enabled = !isLoading,
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                OutlinedTextField(
                    value = stringResource(sortOrder.titleResId),
                    onValueChange = {},
                    enabled = !isLoading,
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.sort_order)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { sortMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    sortOrders.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(stringResource(order.titleResId)) },
                            onClick = {
                                sortMenuExpanded = false
                                onSortOrderChanged(order)
                            },
                        )
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1.dp)
                        .then(Modifier),
                )
            }
            if (isTrackerAvailable) {
                SettingSwitch(
                    title = stringResource(R.string.check_for_new_chapters),
                    checked = isTrackerEnabled,
                    enabled = !isLoading,
                    onCheckedChange = onTrackerChanged,
                )
            }
            SettingSwitch(
                title = stringResource(R.string.show_on_shelf),
                checked = isVisibleOnShelf,
                enabled = !isLoading,
                onCheckedChange = onShelfChanged,
            )
            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.padding(top = 12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
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

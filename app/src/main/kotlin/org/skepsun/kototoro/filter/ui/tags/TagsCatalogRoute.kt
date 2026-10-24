package org.skepsun.kototoro.filter.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.TvModalBottomSheet
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.filter.ui.model.TagCatalogItem
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsCatalogRoute(
    filter: FilterCoordinator,
    isExcludeTag: Boolean,
    groupTitle: String?,
    onDismiss: () -> Unit,
    viewModel: TagsCatalogViewModel = hiltViewModel(
        key = "tags-catalog-${filter.mangaSource.name}-$isExcludeTag-${groupTitle.orEmpty()}",
        creationCallback = { factory: TagsCatalogViewModel.Factory ->
            factory.create(
                filter = filter,
                isExcludeTag = isExcludeTag,
                groupTitle = groupTitle,
            )
        },
    ),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    TvModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        TagsCatalogContent(
            title = viewModel.sheetTitle,
            viewModel = viewModel,
            onDismiss = onDismiss,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun TagsCatalogContent(
    title: String?,
    viewModel: TagsCatalogViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.content.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title ?: LocalContext.current.getString(R.string.genres),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            label = { Text(LocalContext.current.getString(R.string.search)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        TagsCatalogList(
            items = items,
            onTagClick = { item -> viewModel.handleTagClick(item.tag, item.isChecked) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TagsCatalogList(
    items: List<ListModel>,
    onTagClick: (TagCatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items = items,
            key = { item -> item::class.java.name + ":" + item.hashCode() },
            contentType = { item -> item::class.java.name },
        ) { item ->
            when (item) {
                is ListHeader -> {
                    Text(
                        text = item.getText(context)?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }

                is TagCatalogItem -> {
                    TagCatalogRow(
                        item = item,
                        onClick = { onTagClick(item) },
                    )
                }

                LoadingState -> {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }

                is ErrorState -> {
                    Text(
                        text = item.exception.getDisplayMessage(context.resources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TagCatalogRow(
    item: TagCatalogItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.isChecked,
            onCheckedChange = { onClick() },
        )
        Text(
            text = item.tag.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}

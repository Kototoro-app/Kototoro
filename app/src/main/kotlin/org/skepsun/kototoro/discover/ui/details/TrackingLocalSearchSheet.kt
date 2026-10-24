package org.skepsun.kototoro.discover.ui.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvTabRing
import org.skepsun.kototoro.core.ui.compose.TvModalBottomSheet
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingLocalSearchSheet(
    availableSources: List<ContentSourceInfo>,
    selectedSourceName: String?,
    results: Map<String, LocalSearchState>,
    initialQuery: String,
    currentContent: Content,
    onSourceSelected: (String) -> Unit,
    onSearch: (String) -> Unit,
    onCandidateClick: (Content) -> Unit,
    onMigrateClick: (Content) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var currentQuery by remember(initialQuery) { mutableStateOf(initialQuery) }
    var pendingMigrationTarget by remember { mutableStateOf<Content?>(null) }

    val closeSheet = {
        coroutineScope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                onDismissRequest()
            }
        }
    }

    TvModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Search Bar
            OutlinedTextField(
                value = currentQuery,
                onValueChange = { currentQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (currentQuery.isNotEmpty()) {
                        IconButton(onClick = { currentQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                },
                singleLine = true,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch(currentQuery) }
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
            )

            if (availableSources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(164.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.nothing_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@TvModalBottomSheet
            }

            val selectedIndex = availableSources.indexOfFirst { it.mangaSource.name == selectedSourceName }
                .coerceAtLeast(0)

            LaunchedEffect(selectedSourceName, availableSources) {
                if (selectedSourceName == null && availableSources.isNotEmpty()) {
                    onSourceSelected(availableSources.first().mangaSource.name)
                }
            }

            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.0f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                edgePadding = 16.dp,
                indicator = { positions ->
                    if (selectedIndex < positions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(positions[selectedIndex]),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                divider = {},
            ) {
                availableSources.forEachIndexed { index, info ->
                    val sourceTitle = rememberResolvedSourceTitle(info.mangaSource)
                    Tab(
                        modifier = Modifier.tvTabRing(),
                        selected = index == selectedIndex,
                        onClick = { onSourceSelected(info.mangaSource.name) },
                        text = {
                            Text(
                                text = sourceTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }

            val currentName = availableSources.getOrNull(selectedIndex)?.mangaSource?.name
            val state = currentName?.let { results[it] }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    null -> Text(
                        text = stringResource(R.string.search),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LocalSearchState.Loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    is LocalSearchState.Loaded -> {
                        if (state.items.isEmpty()) {
                            Text(
                                text = stringResource(R.string.nothing_found),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(state.items, key = { it.id }) { c ->
                                    TrackingCandidateCard(
                                        content = c,
                                        onClick = {
                                            onCandidateClick(c)
                                            closeSheet()
                                        },
                                        onMigrateClick = { pendingMigrationTarget = c },
                                    )
                                }
                            }
                        }
                    }
                    is LocalSearchState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.throwable.localizedMessage ?: stringResource(R.string.error_occurred),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TvTextButton(onClick = { onSearch(currentQuery) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
        }
    }

    pendingMigrationTarget?.let { target ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { pendingMigrationTarget = null },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_replace),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.manga_migration)) },
            text = {
                Text(
                    stringResource(
                        R.string.migrate_confirmation,
                        currentContent.title,
                        currentContent.source.getTitle(context),
                        target.title,
                        target.source.getTitle(context),
                    )
                )
            },
            confirmButton = {
                TvTextButton(onClick = {
                    onMigrateClick(target)
                    pendingMigrationTarget = null
                }) {
                    Text(stringResource(R.string.migrate))
                }
            },
            dismissButton = {
                TvTextButton(onClick = { pendingMigrationTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackingCandidateCard(
    content: Content,
    onClick: () -> Unit,
    onMigrateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coverUrl = content.coverUrl?.takeIf { it.isNotBlank() }
    val coverRequest = remember(content.id, coverUrl, content.source) {
        coverUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .mangaSourceExtra(content.source)
                .build()
        }
    }
    Column(
        modifier = modifier
            .width(108.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onMigrateClick,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (coverRequest == null) {
                Icon(
                    painter = painterResource(R.drawable.ic_placeholder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = content.title,
                    modifier = Modifier.fillMaxWidth().height(152.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = content.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        val chaptersCount = content.chapters?.size ?: 0
        if (chaptersCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.chapters, chaptersCount, chaptersCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        FilledTonalButton(
            onClick = onMigrateClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_replace),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.migrate),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

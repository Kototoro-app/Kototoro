package org.skepsun.kototoro.settings.sources.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getSummary
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.toLocale
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding
import java.util.Locale

@Composable
fun SourcesCatalogRoute(
    viewModel: SourcesCatalogViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenSource: (org.skepsun.kototoro.parsers.model.ContentSource) -> Unit,
) {
    val items by viewModel.content.collectAsStateWithLifecycle()
    val filter by viewModel.appliedFilter.collectAsStateWithLifecycle()
    val locales by viewModel.locales.collectAsStateWithLifecycle()
    val contentTypes by viewModel.contentTypes.collectAsStateWithLifecycle()
    val hasNewSources by viewModel.hasNewSources.collectAsStateWithLifecycle()
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var localeMenuVisible by remember { mutableStateOf(false) }

    SourcesCatalogScreen(
        items = items,
        filter = filter,
        locales = locales,
        contentTypes = contentTypes,
        hasNewSources = hasNewSources,
        searchVisible = searchVisible,
        query = query,
        localeMenuVisible = localeMenuVisible,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSearchClick = { searchVisible = true },
        onSearchClose = { query = ""; viewModel.performSearch(null); searchVisible = false },
        onQueryChange = { query = it; viewModel.performSearch(it) },
        onLocaleClick = { localeMenuVisible = true },
        onLocaleDismiss = { localeMenuVisible = false },
        onLocaleSelected = { localeMenuVisible = false; viewModel.setLocale(it) },
        onNewOnlyChange = { viewModel.setNewOnly(it) },
        onContentTypeChange = { type, selected -> viewModel.setContentType(type, selected) },
        onSourceClick = onOpenSource,
        onAddSource = viewModel::addSource,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SourcesCatalogScreen(
    items: List<org.skepsun.kototoro.list.ui.model.ListModel>,
    filter: SourcesCatalogFilter,
    locales: Set<String?>,
    contentTypes: List<ContentType>,
    hasNewSources: Boolean,
    searchVisible: Boolean,
    query: String,
    localeMenuVisible: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onLocaleClick: () -> Unit,
    onLocaleDismiss: () -> Unit,
    onLocaleSelected: (String?) -> Unit,
    onNewOnlyChange: (Boolean) -> Unit,
    onContentTypeChange: (ContentType, Boolean) -> Unit,
    onSourceClick: (org.skepsun.kototoro.parsers.model.ContentSource) -> Unit,
    onAddSource: (org.skepsun.kototoro.parsers.model.ContentSource) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sortedLocales = remember(locales) { locales.sortedWith(compareBy(nullsFirst(LocaleComparator())) { it?.toLocale() }) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searchVisible) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.search)) },
                            )
                        } else Text(stringResource(R.string.remote_sources))
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
                    actions = {
                        IconButton(onClick = if (searchVisible) onSearchClose else onSearchClick) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                    },
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Box {
                            FilterChip(
                                selected = filter.locale != null,
                                onClick = onLocaleClick,
                                label = {
                                    Text(
                                        filter.locale?.toLocale()?.getDisplayName(context)
                                            ?: stringResource(R.string.all_sources),
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Language, null, Modifier.size(18.dp))
                                },
                            )
                            DropdownMenu(localeMenuVisible, onDismissRequest = onLocaleDismiss) {
                                sortedLocales.forEach { locale ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                locale?.toLocale()?.getDisplayName(context)
                                                    ?: stringResource(R.string.all_sources),
                                            )
                                        },
                                        onClick = { onLocaleSelected(locale) },
                                    )
                                }
                            }
                        }
                    }
                    if (hasNewSources) item {
                        FilterChip(filter.isNewOnly, { onNewOnlyChange(!filter.isNewOnly) }, label = { Text(stringResource(R.string._new)) })
                    }
                    items(contentTypes, key = { it.name }) { type ->
                        FilterChip(type in filter.types, { onContentTypeChange(type, type !in filter.types) }, label = { Text(stringResource(type.titleResId)) })
                    }
                }
                if (items.any { it === LoadingState }) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                items,
                key = { item ->
                    when (item) {
                        is SourceCatalogItem.Source -> "source:${item.source.name}"
                        is SourceCatalogItem.Hint -> "hint:${item.title}"
                        LoadingState -> "loading"
                    }
                },
            ) { item ->
                when (item) {
                    is SourceCatalogItem.Source -> SourceCatalogRow(item, onSourceClick, onAddSource)
                    is SourceCatalogItem.Hint -> Text(stringResource(item.title), Modifier.padding(32.dp))
                    LoadingState -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceCatalogRow(
    item: SourceCatalogItem.Source,
    onSourceClick: (org.skepsun.kototoro.parsers.model.ContentSource) -> Unit,
    onAddSource: (org.skepsun.kototoro.parsers.model.ContentSource) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onSourceClick(item.source) }, onLongClick = { onAddSource(item.source) })
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContentSourceIcon(item.source, Modifier.size(40.dp), contentDescription = item.source.getTitle(context))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.source.getTitle(context), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(item.source.getSummary(context).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.enable))
    }
}

package org.skepsun.kototoro.bookmarks.ui.compose
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.floor
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.ui.AllBookmarksViewModel
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.reader.ui.PageSaveHelper

private fun bookmarkListModelKey(model: Any): String = when (model) {
    is Bookmark -> "bookmark:${model.pageId}"
    is ListHeader -> {
        val contentId = (model.payload as? Content)?.id
        "header:${contentId ?: model.hashCode()}"
    }
    is EmptyState -> "empty_state"
    is LoadingState -> "loading_state"
    else -> "bookmark_item:${model.hashCode()}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBookmarksRoute(
    viewModel: AllBookmarksViewModel,
    contentPadding: PaddingValues,
    appRouter: AppRouter,
    pageSaveHelper: PageSaveHelper
) {
    val items by viewModel.content.collectAsStateWithLifecycle()
    val gridScale by viewModel.gridScale.collectAsStateWithLifecycle()
    var composeSelectionIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val gridState = rememberSaveable(viewModel, saver = LazyGridState.Saver) {
        LazyGridState()
    }

    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    val hapticFeedback = LocalHapticFeedback.current
    val mainActivity = activity as? MainActivity
    val rootView = LocalView.current

    LaunchedEffect(viewModel.onError) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseActivity<*>)?.exceptionResolver
        val observer = SnackbarErrorObserver(host, null, resolver) { resolved ->
            // Cloudflare challenge resolved, content will refresh automatically
        }
        viewModel.onError.collect { event ->
            event?.consume(observer)
        }
    }

    LaunchedEffect(viewModel.onActionDone) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val observer = ReversibleActionObserver(host)
        viewModel.onActionDone.collect { event ->
            event?.consume(observer)
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    val isRefreshing = items.firstOrNull() is LoadingState

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { /* Bookmarks have no pull to refresh */ },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            val posterStyle = remember(gridScale) { compactPosterCardStyle(gridScale) }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val horizontalPadding = contentPadding.calculateLeftPadding(LayoutDirection.Ltr) +
                    contentPadding.calculateRightPadding(LayoutDirection.Ltr)
                val gridSpacing = 6.dp
                val availableWidth = (maxWidth - horizontalPadding).coerceAtLeast(posterStyle.itemWidth)
                val gridColumns = remember(availableWidth, posterStyle.itemWidth, gridSpacing) {
                    floor(
                        ((availableWidth + gridSpacing) / (posterStyle.itemWidth + gridSpacing)).toDouble(),
                    ).toInt().coerceAtLeast(1)
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = items,
                        key = ::bookmarkListModelKey,
                        span = { item ->
                            if (item is ListHeader || item is EmptyState || item is LoadingState) {
                                GridItemSpan(maxLineSpan)
                            } else {
                                GridItemSpan(1)
                            }
                        }
                    ) { listModel ->
                        when (listModel) {
                            is ListHeader -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val manga = listModel.payload as? Content
                                            if (manga != null) {
                                                mainActivity?.resolveDetailsOriginForContent(manga) { origin ->
                                                    when (origin) {
                                                        is DetailsOrigin.EntityGraph -> {
                                                            appRouter.openEntityDetails(
                                                                entityId = origin.entityId,
                                                                initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                                            )
                                                        }
                                                        else -> appRouter.openResolvedDetails(manga, rootView)
                                                    }
                                                } ?: appRouter.openResolvedDetails(manga, rootView)
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 24.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val text = listModel.getText(LocalContext.current)
                                    Text(
                                        text = text?.toString() ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    if (listModel.buttonTextRes != 0) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowRight,
                                            contentDescription = "More",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            is Bookmark -> {
                                val isSelected = listModel.pageId in composeSelectionIds
                                val source = listModel.manga.source.unwrap()
                                val contentType = source.getContentType()
                                val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL

                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    if (isNovel) {
                                        KototoroBookmarkCardNovel(
                                            item = listModel,
                                            cardStyle = posterStyle,
                                            isSelected = isSelected,
                                            onClick = {
                                                if (composeSelectionIds.isNotEmpty()) {
                                                    hapticFeedback.performSelectionHapticFeedback()
                                                    composeSelectionIds = if (isSelected) composeSelectionIds - listModel.pageId else composeSelectionIds + listModel.pageId
                                                } else {
                                                    val intent = ReaderIntent.Builder(activity as Context)
                                                        .bookmark(listModel)
                                                        .incognito()
                                                        .build()
                                                    appRouter.openReader(intent)
                                                    android.widget.Toast.makeText(activity, R.string.incognito_mode, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onLongClick = {
                                                composeSelectionIds = if (isSelected) composeSelectionIds - listModel.pageId else composeSelectionIds + listModel.pageId
                                            },
                                            modifier = Modifier.width(posterStyle.itemWidth),
                                        )
                                    } else {
                                        KototoroBookmarkCardThumb(
                                            item = listModel,
                                            cardStyle = posterStyle,
                                            isSelected = isSelected,
                                            onClick = {
                                                if (composeSelectionIds.isNotEmpty()) {
                                                    hapticFeedback.performSelectionHapticFeedback()
                                                    composeSelectionIds = if (isSelected) composeSelectionIds - listModel.pageId else composeSelectionIds + listModel.pageId
                                                } else {
                                                    val intent = ReaderIntent.Builder(activity as Context)
                                                        .bookmark(listModel)
                                                        .incognito()
                                                        .build()
                                                    appRouter.openReader(intent)
                                                    android.widget.Toast.makeText(activity, R.string.incognito_mode, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onLongClick = {
                                                composeSelectionIds = if (isSelected) composeSelectionIds - listModel.pageId else composeSelectionIds + listModel.pageId
                                            },
                                            modifier = Modifier.width(posterStyle.itemWidth),
                                        )
                                    }
                                }
                            }
                            is EmptyState -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 64.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        painter = painterResource(id = listModel.icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(id = listModel.textPrimary),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(id = listModel.textSecondary),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            is LoadingState -> {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (composeSelectionIds.isNotEmpty()) {
            KototoroSelectionTopBar(
                selectedCount = composeSelectionIds.size,
                isAllNonLocal = true,
                isSingleSelection = composeSelectionIds.size == 1,
                showRemoveOption = true,
                supportedActions = setOf(SelectionAction.SELECT_ALL, SelectionAction.REMOVE, SelectionAction.SAVE),
                onClearSelection = { composeSelectionIds = emptySet() },
                onActionClick = { action ->
                    when (action) {
                        SelectionAction.SELECT_ALL -> {
                            val allIds = items.mapNotNull { (it as? Bookmark)?.pageId }.toSet()
                            composeSelectionIds = allIds
                        }
                        SelectionAction.REMOVE -> {
                            viewModel.removeBookmarks(composeSelectionIds)
                            composeSelectionIds = emptySet()
                        }
                        SelectionAction.SAVE -> {
                            viewModel.savePages(pageSaveHelper, composeSelectionIds)
                            composeSelectionIds = emptySet()
                        }
                        else -> {}
                    }
                }
            )
        }
    }
}

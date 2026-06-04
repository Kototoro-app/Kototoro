package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import dagger.hilt.android.EntryPointAccessors
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.favourites.ui.list.FavouritesListViewModel
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.R
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.selectedFirst
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.ui.compose.resolveSourceTitleForUi

private const val FAVORITES_LOAD_MORE_VISIBLE_THRESHOLD = 48

@Composable
fun KototoroFavoritesListScreen(
    categoryId: Long,
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    onNavigateToDetails: ((Content, String?) -> Unit)? = null,
    sharedTransitionEnabled: Boolean = true,
    isActivePage: Boolean = true,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    onFilterRailOverrideChanged: (CompactFilterRailOverrideState?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val entryPoint = remember(context.applicationContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BaseApp.BaseAppEntryPoint::class.java,
            )
        }.getOrNull()
    }
    val viewModel = hiltViewModel<FavouritesListViewModel>(
        key = "favorites_list_$categoryId",
    )
    LaunchedEffect(categoryId) {
        viewModel.setCategoryId(categoryId)
    }
    val topQuickFilter = viewModel.topQuickFilter.collectAsStateWithLifecycle().value
    val filterRailOverride = remember(topQuickFilter, context, viewModel, entryPoint) {
        topQuickFilter?.let { quickFilter ->
            CompactFilterRailOverrideState(
                items = quickFilter.items.mapIndexedNotNull { index, chip ->
                    val option = chip.data as? ListFilterOption ?: return@mapIndexedNotNull null
                    val sourceOption = option as? ListFilterOption.Source
                    val title = when {
                        sourceOption != null -> resolveSourceTitleForUi(
                            context = context,
                            source = sourceOption.mangaSource,
                            entryPoint = entryPoint,
                        )
                        chip.titleResId != 0 -> context.getString(chip.titleResId)
                        !chip.title.isNullOrBlank() -> chip.title.toString()
                        else -> return@mapIndexedNotNull null
                    }
                    org.skepsun.kototoro.main.ui.compose.CompactFilterRailItem(
                        id = "${option::class.qualifiedName}:${option.hashCode()}:$index",
                        title = title,
                        isSelected = chip.isChecked,
                        source = sourceOption?.mangaSource,
                        onClick = { viewModel.toggleFilterOption(option) },
                    )
                }.selectedFirst(),
            )
        }
    }

    LaunchedEffect(isActivePage, filterRailOverride) {
        if (isActivePage) onFilterRailOverrideChanged(filterRailOverride)
    }

    key(categoryId) {
        AppContentListRoute(
            viewModel = viewModel,
            contentPadding = contentPadding,
            appRouter = appRouter,
            showRemoveOption = true,
            preferredSelectionInlineActions = listOf(
                SelectionAction.PIN,
                SelectionAction.REMOVE,
                SelectionAction.SAVE,
            ),
            removeSelectionActionIconRes = R.drawable.ic_heart_outline,
            removeSelectionActionTitleRes = R.string.remove_from_favourites,
            onTopBarOverrideChanged = onTopBarOverrideChanged,
            onFilterRailOverrideChanged = {},
            emitFilterRailOverride = false,
            sharedTransitionEnabled = sharedTransitionEnabled,
            sharedElementInstanceKey = "main_favorites_$categoryId",
            registerFilterCallback = false, // Parent FavoritesHostScreen manages the centralized callback
            pullRefreshEnabled = false,
            onLoadMore = { viewModel.requestMoreItems() },
            loadMoreVisibleThreshold = FAVORITES_LOAD_MORE_VISIBLE_THRESHOLD,
            onNavigateToDetails = onNavigateToDetails,
            onRemoveSelection = { ids ->
                viewModel.removeFromFavourites(ids)
            },
            onPinSelection = { ids ->
                viewModel.togglePinned(ids)
            },
            onMarkAsCompletedSelection = { items ->
                viewModel.markAsRead(items.map { it.manga }.toSet())
            },
            showQuickFilterInline = true,
        )
    }
}

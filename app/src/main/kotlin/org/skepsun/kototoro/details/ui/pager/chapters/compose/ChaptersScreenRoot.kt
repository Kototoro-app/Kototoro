package org.skepsun.kototoro.details.ui.pager.chapters.compose

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.details.ui.pager.chapters.ChapterGroupsManager
import org.skepsun.kototoro.details.ui.withVolumeHeaders
import org.skepsun.kototoro.local.ui.LocalChaptersRemoveService
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.ReaderNavigationCallback

@Composable
fun ChaptersScreenRoot(
	viewModel: ChaptersPagesViewModel,
	router: AppRouter,
	context: Context,
	viewForSnackbar: View,
	lifecycleOwner: LifecycleOwner,
	isScrollEnabled: Boolean = true,
    detailsPaneState: DetailsPaneState? = null,
    handleSelectionBackPressInternally: Boolean = true,
    onSelectionStateChange: (ChapterSelectionUiState?) -> Unit = {},
) {
	val isGridView by viewModel.isChaptersInGridView.collectAsStateWithLifecycle(initialValue = false)
	val isLoading by viewModel.isLoading.collectAsStateWithLifecycle(initialValue = false)
	val quickFilter by viewModel.quickFilter.collectAsStateWithLifecycle(initialValue = emptyList())
	val emptyReason by viewModel.emptyReason.collectAsStateWithLifecycle(initialValue = null)
	val chapters by viewModel.chapters.collectAsStateWithLifecycle(initialValue = emptyList())
	val selectedBranch by viewModel.selectedBranch.collectAsStateWithLifecycle(initialValue = null)
	var qualityProbeResult by remember { mutableStateOf<ChaptersPagesViewModel.QualityProbeResult?>(null) }

	val appContext = context.applicationContext
	val settings = remember(appContext) { AppSettings(appContext) }
	val gridSize by settings.observeAsState(AppSettings.KEY_GRID_SIZE_PAGES) { gridSizePages }

	val gridScale = (gridSize / 100f)
	
	val chaptersWithHeaders = remember(chapters) {
		chapters.withVolumeHeaders(context)
	}
	
	var groupsVersion by remember { mutableIntStateOf(0) }
	
	val groupsManager = remember { ChapterGroupsManager() }
	
	val collapsedChapters = remember(chaptersWithHeaders, groupsVersion) {
		groupsManager.applyCollapsedState(chaptersWithHeaders)
	}

	val selectedItemIds = remember { mutableStateListOf<Long>() }
	var hasShownSelectionHint by remember { mutableStateOf(false) }
    val selectedIds = remember(selectedItemIds.toList()) {
        selectedItemIds.toSet()
    }
    val visibleChapterIds = remember(chapters) {
        chapters.mapTo(linkedSetOf()) { it.chapter.id }
    }
    val visibleSelectableIds = remember(collapsedChapters) {
        collapsedChapters
            .filterIsInstance<ChapterListItem>()
            .map { it.chapter.id }
    }
    val initialCurrentChapterId = remember(collapsedChapters) {
        collapsedChapters
            .filterIsInstance<ChapterListItem>()
            .firstOrNull { it.isCurrent }
            ?.chapter
            ?.id
    }
    val selectedItems = remember(chapters, selectedIds) {
        chapters.filter { it.chapter.id in selectedIds }
    }

    BackHandler(enabled = selectedIds.isNotEmpty() && handleSelectionBackPressInternally) {
        selectedItemIds.clear()
    }

	DisposableEffect(Unit) {
		viewModel.onShowVideoQualityDialog.observeEvent(lifecycleOwner) { result ->
			qualityProbeResult = result
		}
        onDispose {
            onSelectionStateChange(null)
        }
	}

	LaunchedEffect(chapters, quickFilter, selectedBranch) {
		if (chapters.isNotEmpty()) {
			return@LaunchedEffect
		}
		val branches = quickFilter.mapNotNull { chip ->
			(chip.data as? org.skepsun.kototoro.list.domain.ListFilterOption.Branch)?.titleText
		}
		if (branches.isNotEmpty() && selectedBranch !in branches) {
			viewModel.setSelectedBranch(branches.first())
		}
	}
    LaunchedEffect(visibleChapterIds) {
        selectedItemIds.retainAll(visibleChapterIds)
    }
	LaunchedEffect(selectedIds.isNotEmpty()) {
		if (selectedIds.isNotEmpty() && !hasShownSelectionHint) {
			hasShownSelectionHint = true
			try {
				Snackbar.make(
					viewForSnackbar,
					R.string.chapter_range_selection_hint,
					Snackbar.LENGTH_LONG,
				).show()
			} catch (e: IllegalArgumentException) {
				e.printStackTraceDebug()
				Toast.makeText(
					context,
					R.string.chapter_range_selection_hint,
					Toast.LENGTH_LONG,
				).show()
			}
		}
	}
    val handleSelectionAction: (Int) -> Unit = remember(
        context,
        router,
        selectedIds,
        selectedItems,
        selectedItemIds,
        viewForSnackbar,
        viewModel,
    ) {
        { actionId ->
            if (selectedIds.isEmpty()) {
                Unit
            } else {
                val removeBookmarks = selectedItems.isNotEmpty() && selectedItems.all { it.isBookmarked }
                when (actionId) {
                    R.id.action_save -> {
                        val manga = viewModel.mangaDetails.value?.toContent()
                        if (manga?.source?.getContentType() == ContentType.VIDEO) {
                            viewModel.probeAndDownload(selectedIds)
                        } else {
                            router.askForDownloadOverMeteredNetwork { allow ->
                                viewModel.download(selectedIds, allow)
                            }
                        }
                    }

                    R.id.action_mark_current -> {
                        if (selectedIds.size == 1) {
                            viewModel.markChapterAsCurrent(selectedIds.first())
                        }
                    }

                    R.id.action_bookmark -> {
                        viewModel.setBookmarksForChapters(selectedIds, removeExisting = removeBookmarks)
                    }

                    R.id.action_delete -> {
                        val manga = viewModel.getContentOrNull()
                        when {
                            manga == null -> Unit
                            selectedIds.size == manga.chapters?.size -> viewModel.deleteLocal()
                            else -> {
                                LocalChaptersRemoveService.start(context, manga, selectedIds)
                                try {
                                    Snackbar.make(
                                        viewForSnackbar,
                                        R.string.chapters_will_removed_background,
                                        Snackbar.LENGTH_LONG,
                                    ).show()
                                } catch (e: IllegalArgumentException) {
                                    e.printStackTraceDebug()
                                    Toast.makeText(
                                        context,
                                        R.string.chapters_will_removed_background,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                }
                selectedItemIds.clear()
            }
        }
    }
    val selectionState = remember(selectedIds, selectedItems, visibleSelectableIds, handleSelectionAction) {
        if (selectedIds.isEmpty()) {
            null
        } else {
            ChapterSelectionUiState(
                selectedCount = selectedIds.size,
                canSelectAll = selectedIds.size < visibleSelectableIds.size,
                canDownload = selectedItems.isNotEmpty() && selectedItems.all { !it.isDownloaded && !it.chapter.source.isLocal },
                canDelete = selectedItems.isNotEmpty() && selectedItems.all { it.isDownloaded || it.chapter.source.isLocal },
                canMarkCurrent = selectedItems.size == 1 && !selectedItems.first().isCurrent,
                canBookmark = selectedItems.isNotEmpty(),
                isBookmarkRemoveAction = selectedItems.isNotEmpty() && selectedItems.all { it.isBookmarked },
                onClearSelection = { selectedItemIds.clear() },
                onSelectAll = {
                    selectedItemIds.clear()
                    selectedItemIds.addAll(visibleSelectableIds)
                },
                onDownload = { handleSelectionAction(R.id.action_save) },
                onDelete = { handleSelectionAction(R.id.action_delete) },
                onMarkCurrent = { handleSelectionAction(R.id.action_mark_current) },
                onBookmark = { handleSelectionAction(R.id.action_bookmark) },
            )
        }
    }

    SideEffect {
        onSelectionStateChange(selectionState)
    }

	qualityProbeResult?.let { result ->
		VideoQualityDialog(
			qualities = result.qualities,
			onDismissRequest = { qualityProbeResult = null },
			onConfirm = { quality ->
				qualityProbeResult = null
				router.askForDownloadOverMeteredNetwork { allow ->
					viewModel.download(result.snapshot, allow, quality)
				}
			},
		)
	}

		ChaptersScreen(
			items = collapsedChapters,
			isGridView = isGridView,
			isScrollEnabled = isScrollEnabled,
			detailsPaneState = detailsPaneState,
			gridScale = gridScale,
			selectedItemIds = selectedIds,
			filterChips = quickFilter,
			isLoading = isLoading,
		emptyMessageResId = emptyReason?.msgResId,
		initialChapterId = initialCurrentChapterId,
		onItemClick = { item ->
			if (selectedIds.isNotEmpty()) {
				if (selectedIds.contains(item.chapter.id)) {
					selectedItemIds.remove(item.chapter.id)
				} else {
					selectedItemIds.add(item.chapter.id)
				}
			} else {
				val manga = viewModel.getContentOrNull() ?: return@ChaptersScreen
				val navigationCallback = (context as? ReaderNavigationCallback)
					?: (context.findActivity() as? ReaderNavigationCallback)
				if (navigationCallback?.onChapterSelected(item.chapter) == true) {
					return@ChaptersScreen
				}
				val targetState = org.skepsun.kototoro.reader.ui.ReaderState(item.chapter.id, 0, 0)
				(viewModel as? DetailsViewModel)?.recordDetailsJump(targetState, "detail_chapter")
				router.openReader(
					ReaderIntent.Builder(context)
						.manga(manga)
						.state(targetState)
						.build()
				)
			}
		},
		onItemLongClick = { item ->
			val range = resolveVisibleSelectionRange(
				visibleChapterIds = visibleSelectableIds,
				selectedIds = selectedIds,
				targetId = item.chapter.id,
			)
			if (range != null) {
				range.forEach { id ->
					if (!selectedItemIds.contains(id)) {
						selectedItemIds.add(id)
					}
				}
			} else if (selectedItemIds.contains(item.chapter.id)) {
				selectedItemIds.remove(item.chapter.id)
			} else {
				selectedItemIds.add(item.chapter.id)
			}
		},
		onHeaderClick = { header ->
			if (header.isCollapsible) {
				groupsManager.toggleGroup(header.groupId)
				groupsVersion++
			}
		},
		onFilterChipClick = { chip ->
			val branch = chip.data as? org.skepsun.kototoro.list.domain.ListFilterOption.Branch
			if (branch != null) {
				viewModel.setSelectedBranch(branch.titleText)
			}
		},
		onSelectionActionClick = handleSelectionAction,
		onClearSelection = { selectedItemIds.clear() }
	)
}

private fun resolveVisibleSelectionRange(
	visibleChapterIds: List<Long>,
	selectedIds: Set<Long>,
	targetId: Long,
): List<Long>? {
	if (selectedIds.size != 2 || targetId in selectedIds) {
		return null
	}
	val endpointIndexes = selectedIds.map { visibleChapterIds.indexOf(it) }
	if (endpointIndexes.any { it == -1 }) {
		return null
	}
	val targetIndex = visibleChapterIds.indexOf(targetId)
	if (targetIndex == -1) {
		return null
	}
	val start = endpointIndexes.minOrNull() ?: return null
	val end = endpointIndexes.maxOrNull() ?: return null
	if (targetIndex !in start..end) {
		return null
	}
	return visibleChapterIds.subList(start, end + 1)
}

@Composable
private fun VideoQualityDialog(
	qualities: List<String>,
	onDismissRequest: () -> Unit,
	onConfirm: (String?) -> Unit,
) {
	val options = remember(qualities) { listOf<String?>(null) + qualities }
	var selectedIndex by remember(options) { mutableIntStateOf(0) }

	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(text = stringResource(R.string.video_quality)) },
		text = {
			Column(modifier = Modifier.selectableGroup()) {
				options.forEachIndexed { index, option ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.selectable(
								selected = index == selectedIndex,
								onClick = { selectedIndex = index },
								role = Role.RadioButton,
							)
							.padding(vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(
							selected = index == selectedIndex,
							onClick = null,
						)
						Text(
							text = option ?: stringResource(R.string.system_default),
							style = MaterialTheme.typography.bodyLarge,
							modifier = Modifier.padding(start = 16.dp),
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					onConfirm(options[selectedIndex])
				},
			) {
				Text(text = stringResource(R.string.download))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismissRequest) {
				Text(text = stringResource(android.R.string.cancel))
			}
		},
	)
}

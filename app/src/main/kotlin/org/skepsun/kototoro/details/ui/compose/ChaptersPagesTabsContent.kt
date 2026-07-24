package org.skepsun.kototoro.details.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.details.ui.model.DetailsChapterSourceTab
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.model.toListItem
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import org.skepsun.kototoro.details.ui.pager.bookmarks.compose.BookmarksScreenRoot
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChaptersScreen
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChaptersScreenRoot
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.details.ui.pager.pages.compose.PagesScreenRoot
import org.skepsun.kototoro.details.ui.withVolumeHeaders
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

const val DETAILS_TAB_CHAPTERS = 0
const val DETAILS_TAB_PAGES = 1
const val DETAILS_TAB_BOOKMARKS = 2

private data class DetailsTabSpec(
	val tabId: Int,
	val titleResId: Int,
	val iconResId: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChaptersPagesTabsContent(
	viewModel: ChaptersPagesViewModel,
	pagesViewModel: PagesViewModel,
	bookmarksViewModel: BookmarksViewModel,
	settings: AppSettings,
	appRouter: AppRouter,
	pageSaveHelper: PageSaveHelper,
	metadataChapterTabs: List<DetailsChapterSourceTab> = emptyList(),
	readingChapterTabs: List<DetailsChapterSourceTab> = emptyList(),
	onSelectMetadataChapterTab: (DetailsChapterSourceTab) -> Unit = {},
	onSelectReadingChapterTab: (DetailsChapterSourceTab) -> Unit = {},
	initialPage: Int = 0,
	selectedTabId: Int? = null,
	showTabStrip: Boolean = true,
	isSheetFullyExpanded: Boolean = true,
	isChapterListScrollEnabled: Boolean = true,
	handleSelectionBackPressInternally: Boolean = true,
    detailsPaneState: DetailsPaneState? = null,
    pageThumbnailAspectRatio: Float = 0.7f,
    chapterQuery: String = "",
    isChapterSearchVisible: Boolean = false,
    onChapterQueryChange: ((String) -> Unit)? = null,
    onChapterSelectionStateChange: (ChapterSelectionUiState?) -> Unit = {},
	onSelectedTabIdChange: ((Int) -> Unit)? = null,
	isMergeRepeatedChapters: Boolean = false,
) {
	val mangaDetails by viewModel.mangaDetails.collectAsStateWithLifecycle()
	val source = mangaDetails?.toContent()?.source
	val contentType = source?.getContentType()
	val emptyReason by viewModel.emptyReason.collectAsStateWithLifecycle(initialValue = null)

	val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL
	val isVideo = contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO

	val isPagesTabEnabled = settings.isPagesTabEnabled && !isNovel && !isVideo
	val isBookmarksTabEnabled = !isVideo
	val isDownloadedFilterVisible = mangaDetails?.local != null

	val tabsList = remember(isPagesTabEnabled, isBookmarksTabEnabled) {
		buildList {
			add(DetailsTabSpec(tabId = DETAILS_TAB_CHAPTERS, titleResId = R.string.chapters, iconResId = R.drawable.ic_list))
			if (isPagesTabEnabled) {
				add(DetailsTabSpec(tabId = DETAILS_TAB_PAGES, titleResId = R.string.pages, iconResId = R.drawable.ic_grid))
			}
			if (isBookmarksTabEnabled) {
				add(DetailsTabSpec(tabId = DETAILS_TAB_BOOKMARKS, titleResId = R.string.bookmarks, iconResId = R.drawable.ic_bookmark))
			}
		}
	}

	val context = LocalContext.current
	val router = appRouter
	val viewForSnackbar = LocalView.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val coroutineScope = rememberCoroutineScope()

	Surface(
		modifier = Modifier.fillMaxSize(),
		color = Color.Transparent,
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			if (tabsList.isEmpty()) return@Column

			val requestedIndex = selectedTabId?.let { requestedId ->
				tabsList.indexOfFirst { it.tabId == requestedId }.takeIf { it >= 0 }
			}
			val validInitialPage = (requestedIndex ?: initialPage).coerceIn(0, tabsList.lastIndex)
			val pagerState = rememberPagerState(
				initialPage = validInitialPage,
				pageCount = { tabsList.size },
			)
			val safeCurrentPage = pagerState.currentPage.coerceIn(0, tabsList.lastIndex)
			val currentTabId = tabsList[safeCurrentPage].tabId

			LaunchedEffect(tabsList) {
				if (pagerState.currentPage !in tabsList.indices) {
					pagerState.scrollToPage(safeCurrentPage)
				}
			}

			LaunchedEffect(selectedTabId, tabsList) {
				if (selectedTabId == null) {
					return@LaunchedEffect
				}
				val targetIndex = tabsList.indexOfFirst { it.tabId == selectedTabId }
				if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
					pagerState.scrollToPage(targetIndex)
				}
			}

			LaunchedEffect(safeCurrentPage, tabsList) {
				onSelectedTabIdChange?.invoke(tabsList[safeCurrentPage].tabId)
			}

			LaunchedEffect(currentTabId) {
				if (currentTabId != DETAILS_TAB_CHAPTERS && chapterQuery.isNotEmpty()) {
					onChapterQueryChange?.invoke("")
				}
			}

			if (showTabStrip && tabsList.size > 1) {
				DetailsTabsRow(
					selectedTabIndex = safeCurrentPage,
					tabs = tabsList,
					onTabClick = { index ->
						coroutineScope.launch {
							pagerState.animateScrollToPage(index)
						}
					},
				)
			}

			ChaptersPagesToolbar(
				currentTabId = currentTabId,
				chapterQuery = chapterQuery,
				onChapterQueryChange = onChapterQueryChange ?: {},
				isChapterSearchVisible = isChapterSearchVisible,
				isSearchVisible = emptyReason == null,
			)

			HorizontalPager(
				state = pagerState,
				modifier = Modifier.weight(1f).fillMaxWidth(),
			) { page ->
				when (tabsList.getOrNull(page)?.tabId ?: return@HorizontalPager) {
					DETAILS_TAB_CHAPTERS -> DetailsChapterPanels(
						viewModel = viewModel,
						router = router,
						context = context,
						viewForSnackbar = viewForSnackbar,
						lifecycleOwner = lifecycleOwner,
						metadataChapterTabs = metadataChapterTabs,
						readingChapterTabs = readingChapterTabs,
						chapterQuery = chapterQuery,
						onSelectMetadataChapterTab = onSelectMetadataChapterTab,
						onSelectReadingChapterTab = onSelectReadingChapterTab,
						isScrollEnabled = isChapterListScrollEnabled,
						detailsPaneState = detailsPaneState,
                        handleSelectionBackPressInternally = handleSelectionBackPressInternally,
                        onChapterSelectionStateChange = onChapterSelectionStateChange,
						isMergeRepeatedChapters = isMergeRepeatedChapters,
					)
					DETAILS_TAB_PAGES -> PagesScreenRoot(
						activityViewModel = viewModel,
						router = router,
						context = context,
						pageSaveHelper = pageSaveHelper,
						viewForSnackbar = viewForSnackbar,
						lifecycleOwner = lifecycleOwner,
						viewModel = pagesViewModel,
						detailsPaneState = detailsPaneState,
						thumbnailAspectRatio = pageThumbnailAspectRatio,
					)
					DETAILS_TAB_BOOKMARKS -> BookmarksScreenRoot(
						activityViewModel = viewModel,
						router = router,
						context = context,
						viewModel = bookmarksViewModel,
						detailsPaneState = detailsPaneState,
					)
				}
			}
		}
	}
}

private enum class ChapterPanelMode {
	METADATA,
	READING,
}

@Composable
private fun DetailsChapterPanels(
	viewModel: ChaptersPagesViewModel,
	router: AppRouter,
	context: android.content.Context,
	viewForSnackbar: android.view.View,
	lifecycleOwner: androidx.lifecycle.LifecycleOwner,
	metadataChapterTabs: List<DetailsChapterSourceTab>,
	readingChapterTabs: List<DetailsChapterSourceTab>,
	chapterQuery: String,
	onSelectMetadataChapterTab: (DetailsChapterSourceTab) -> Unit,
	onSelectReadingChapterTab: (DetailsChapterSourceTab) -> Unit,
	isScrollEnabled: Boolean,
    detailsPaneState: DetailsPaneState? = null,
    handleSelectionBackPressInternally: Boolean,
    onChapterSelectionStateChange: (ChapterSelectionUiState?) -> Unit,
	isMergeRepeatedChapters: Boolean,
) {
	val availableModes = remember(metadataChapterTabs, readingChapterTabs) {
		buildList {
			if (metadataChapterTabs.isNotEmpty()) {
				add(ChapterPanelMode.METADATA)
			}
			add(ChapterPanelMode.READING)
		}
	}
	var selectedModeName by rememberSaveable { mutableStateOf(availableModes.first().name) }
	val selectedMode = availableModes.firstOrNull { it.name == selectedModeName } ?: availableModes.first()

	LaunchedEffect(availableModes) {
		if (availableModes.none { it.name == selectedModeName }) {
			selectedModeName = availableModes.first().name
		}
	}
	val readingChapterTitleRes = remember(readingChapterTabs) {
		val selectedReadingContentType = readingChapterTabs.firstOrNull { it.isSelected }?.source?.getContentType()
			?: readingChapterTabs.firstOrNull()?.source?.getContentType()
		if (selectedReadingContentType == ContentType.VIDEO || selectedReadingContentType == ContentType.HENTAI_VIDEO) {
			R.string.details_playback_chapters
		} else {
			R.string.details_reading_chapters
		}
	}

	Column(modifier = Modifier.fillMaxSize()) {
		if (availableModes.size > 1) {
			ChapterModeTabsRow(
				availableModes = availableModes,
				selectedMode = selectedMode,
				readingChapterTitleRes = readingChapterTitleRes,
				onSelectMode = { selectedModeName = it.name },
			)
		}

		when (selectedMode) {
			ChapterPanelMode.METADATA -> MetadataChapterPanel(
				tabs = metadataChapterTabs,
				chapterQuery = chapterQuery,
				onSelectTab = onSelectMetadataChapterTab,
				onOpenBrowser = { url -> router.openBrowser(url, null, null) },
				isScrollEnabled = isScrollEnabled,
			)

			ChapterPanelMode.READING -> {
				if (readingChapterTabs.size > 1 && !isMergeRepeatedChapters) {
					ChapterSourceTabsRow(
						tabs = readingChapterTabs,
						onSelectTab = onSelectReadingChapterTab,
					)
				}
				ChaptersScreenRoot(
					viewModel = viewModel,
					router = router,
					context = context,
					viewForSnackbar = viewForSnackbar,
					lifecycleOwner = lifecycleOwner,
					isScrollEnabled = isScrollEnabled,
                    detailsPaneState = detailsPaneState,
                    handleSelectionBackPressInternally = handleSelectionBackPressInternally,
                    onSelectionStateChange = onChapterSelectionStateChange,
				)
			}
		}
	}
}

@Composable
private fun DetailsTabsRow(
	selectedTabIndex: Int,
	tabs: List<DetailsTabSpec>,
	onTabClick: (Int) -> Unit,
) {
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	val shape = RoundedCornerShape(if (expressive) 24.dp else 0.dp)
	val modifier = Modifier
		.fillMaxWidth()
		.padding(horizontal = 12.dp, vertical = 8.dp)
		.then(
			if (expressive) {
				Modifier
					.background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
					.padding(4.dp)
			} else {
				Modifier
			},
		)
	SecondaryTabRow(
		selectedTabIndex = selectedTabIndex,
		containerColor = Color.Transparent,
		contentColor = MaterialTheme.colorScheme.primary,
		modifier = modifier,
	) {
		tabs.forEachIndexed { index, tab ->
			Tab(
				selected = selectedTabIndex == index,
				onClick = { onTabClick(index) },
				icon = {
					Icon(
						painter = painterResource(tab.iconResId),
						contentDescription = stringResource(tab.titleResId),
					)
				},
			)
		}
	}
}

@Composable
private fun ChapterModeTabsRow(
	availableModes: List<ChapterPanelMode>,
	selectedMode: ChapterPanelMode,
	readingChapterTitleRes: Int,
	onSelectMode: (ChapterPanelMode) -> Unit,
) {
	SecondaryTabRow(
		selectedTabIndex = availableModes.indexOf(selectedMode),
		containerColor = Color.Transparent,
		contentColor = MaterialTheme.colorScheme.primary,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp, vertical = 8.dp),
	) {
		availableModes.forEachIndexed { index, mode ->
			Tab(
				selected = mode == selectedMode,
				onClick = { onSelectMode(mode) },
				text = {
					Text(
						stringResource(
							if (index == 0 && mode == ChapterPanelMode.METADATA) {
								R.string.details_metadata_chapters
							} else if (mode == ChapterPanelMode.READING) {
								readingChapterTitleRes
							} else {
								R.string.details_metadata_chapters
							},
						),
					)
				},
			)
		}
	}
}

@Composable
private fun MetadataChapterPanel(
	tabs: List<DetailsChapterSourceTab>,
	chapterQuery: String,
	onSelectTab: (DetailsChapterSourceTab) -> Unit,
	onOpenBrowser: (String) -> Unit,
	isScrollEnabled: Boolean,
) {
	val context = LocalContext.current
	val viewModel = remember { mutableStateOf<org.skepsun.kototoro.parsers.model.ContentChapter?>(null) }
	val selectedTab = tabs.firstOrNull { it.isSelected } ?: tabs.firstOrNull()
	val chapters = selectedTab?.chapters.orEmpty()
	val isGridView = false
	val filteredChapters = remember(chapters, chapterQuery) {
		if (chapterQuery.isBlank()) {
			chapters
		} else {
			chapters.filter { chapter ->
				chapter.title?.contains(chapterQuery, ignoreCase = true) == true ||
					chapter.numberString()?.contains(chapterQuery, ignoreCase = true) == true ||
					chapter.url.contains(chapterQuery, ignoreCase = true)
			}
		}
	}
	val items = remember(filteredChapters, context) {
		filteredChapters.map {
			it.toListItem(
				isCurrent = false,
				isUnread = true,
				isNew = false,
				isDownloaded = false,
				isBookmarked = false,
				isGrid = isGridView,
			)
		}.withVolumeHeaders(context)
	}

	Column(modifier = Modifier.fillMaxSize()) {
		if (tabs.size > 1) {
			ChapterSourceTabsRow(
				tabs = tabs,
				onSelectTab = onSelectTab,
			)
		}
		ChaptersScreen(
			items = items,
			isGridView = isGridView,
			isScrollEnabled = isScrollEnabled,
			gridScale = 1.0f,
			selectedItemIds = emptySet(),
			filterChips = emptyList(),
			isLoading = false,
			emptyMessageResId = R.string.no_chapters,
			initialChapterId = null,
			onItemClick = { viewModel.value = it.chapter },
			onItemLongClick = {},
			onHeaderClick = {},
			onFilterChipClick = {},
			onSelectionActionClick = {},
			onClearSelection = {},
		)
	}

	viewModel.value?.let { chapter ->
		MetadataChapterDialog(
			chapter = chapter,
			sourceTab = selectedTab,
			onDismissRequest = { viewModel.value = null },
			onOpenBrowser = {
				viewModel.value = null
				onOpenBrowser(chapter.url)
			},
		)
	}
}

@Composable
private fun ChapterSourceTabsRow(
	tabs: List<DetailsChapterSourceTab>,
	onSelectTab: (DetailsChapterSourceTab) -> Unit,
) {
	val expressive = LocalMaterialExpressiveComponentsEnabled.current
	LazyRow(
		modifier = Modifier.fillMaxWidth(),
		contentPadding = PaddingValues(
			horizontal = if (expressive) 12.dp else 16.dp,
			vertical = if (expressive) 8.dp else 10.dp,
		),
		horizontalArrangement = Arrangement.spacedBy(if (expressive) 6.dp else 8.dp),
	) {
		items(tabs, key = { it.key }) { tab ->
			val labelText = when {
				tab.trackingService != null -> stringResource(tab.trackingService.titleResId)
				tab.source != null -> rememberResolvedSourceTitle(tab.source)
				else -> tab.key
			}
			FilterChip(
				selected = tab.isSelected,
				onClick = { onSelectTab(tab) },
				shape = RoundedCornerShape(if (expressive) 18.dp else 8.dp),
				colors = if (expressive) {
					FilterChipDefaults.filterChipColors(
						containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
						selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
						labelColor = MaterialTheme.colorScheme.onSurface,
						selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
						iconColor = MaterialTheme.colorScheme.onSurface,
						selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
					)
				} else {
					FilterChipDefaults.filterChipColors()
				},
				label = {
					Text(text = labelText)
				},
				leadingIcon = {
					when {
						tab.trackingService != null -> {
							Icon(
								painter = painterResource(tab.trackingService.iconResId),
								contentDescription = null,
							)
						}

						tab.source != null -> {
							ContentSourceIcon(
								source = tab.source,
								modifier = Modifier.size(18.dp),
								styleResId = R.style.FaviconDrawable_Chip,
							)
						}
					}
				},
			)
		}
	}
}

@Composable
private fun MetadataChapterDialog(
	chapter: org.skepsun.kototoro.parsers.model.ContentChapter,
	sourceTab: DetailsChapterSourceTab?,
	onDismissRequest: () -> Unit,
	onOpenBrowser: () -> Unit,
) {
	androidx.compose.material3.AlertDialog(
		onDismissRequest = onDismissRequest,
		title = {
			Text(chapter.title ?: stringResource(R.string.chapters))
		},
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				sourceTab?.trackingService?.let { service ->
					Text(
						text = stringResource(R.string.details_metadata_source_label, stringResource(service.titleResId)),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				chapter.numberString()?.let { number ->
					Text(
						text = stringResource(R.string.details_chapter_number_label, number),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				Text(
					text = stringResource(R.string.details_metadata_chapter_hint),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				if (chapter.url.isNotBlank()) {
					Text(
						text = chapter.url,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
			}
		},
		confirmButton = {
			if (chapter.url.isNotBlank()) {
				androidx.compose.material3.TextButton(onClick = onOpenBrowser) {
					Text(stringResource(R.string.open_in_browser))
				}
			}
		},
		dismissButton = {
			androidx.compose.material3.TextButton(onClick = onDismissRequest) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}

@Composable
private fun ChaptersPagesToolbar(
	currentTabId: Int,
	chapterQuery: String,
	onChapterQueryChange: (String) -> Unit,
	isChapterSearchVisible: Boolean,
	isSearchVisible: Boolean,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		if (currentTabId == DETAILS_TAB_CHAPTERS && isSearchVisible && isChapterSearchVisible) {
			OutlinedTextField(
				value = chapterQuery,
				onValueChange = onChapterQueryChange,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 12.dp),
				singleLine = true,
				label = { Text(stringResource(R.string.search_chapters)) },
			)
		}
		if (currentTabId != DETAILS_TAB_CHAPTERS || (isSearchVisible && isChapterSearchVisible)) {
			HorizontalDivider()
		}
	}
}

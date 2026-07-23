package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import org.skepsun.kototoro.reader.novel.NovelPage
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.TextDirection as NovelTextDirection
import org.skepsun.kototoro.image.ui.NovelInlineImageLoader
import org.skepsun.kototoro.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeNovelReader(
	pages: List<NovelPage>,
	settings: NovelReaderSettings,
	initialPage: Int,
	onPageChanged: (NovelPage) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (pages.isEmpty()) return
	if (settings.readingMode == ReadingMode.SCROLL) {
		ComposeNovelContinuousReader(pages, settings, initialPage, onPageChanged, modifier)
	} else {
		val pagerState = rememberPagerState(
			initialPage = initialPage.coerceIn(pages.indices),
			pageCount = pages::size,
		)
		LaunchedEffect(pagerState, pages) {
			snapshotFlow { pagerState.settledPage }
				.distinctUntilChanged()
				.collect { pages.getOrNull(it)?.let(onPageChanged) }
		}
		HorizontalPager(
			state = pagerState,
			modifier = modifier.fillMaxSize(),
			key = { pages[it].globalIndex },
		) { index ->
			NovelPageText(page = pages[index], settings = settings, modifier = Modifier.fillMaxSize())
		}
	}
}

@Composable
private fun ComposeNovelContinuousReader(
	pages: List<NovelPage>,
	settings: NovelReaderSettings,
	initialPage: Int,
	onPageChanged: (NovelPage) -> Unit,
	modifier: Modifier,
) {
	val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(pages.indices))
	LaunchedEffect(listState, pages) {
		snapshotFlow { listState.firstVisibleItemIndex }
			.distinctUntilChanged()
			.collect { pages.getOrNull(it)?.let(onPageChanged) }
	}
	LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
		items(count = pages.size, key = { pages[it].globalIndex }) { index ->
			NovelPageText(page = pages[index], settings = settings, modifier = Modifier.fillParentMaxWidth())
		}
	}
}

@Composable
private fun NovelPageText(
	page: NovelPage,
	settings: NovelReaderSettings,
	modifier: Modifier = Modifier,
) {
	val horizontal = settings.marginHorizontal.dp
	val vertical = settings.marginVertical.dp
	val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
	val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
	Box(modifier = modifier.padding(PaddingValues(horizontal = horizontal, vertical = vertical))) {
		Text(
			text = page.text,
			style = MaterialTheme.typography.bodyLarge.copy(
				fontSize = settings.fontSizeSp.sp,
				lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
				textDirection = direction,
			),
			textAlign = alignment,
			modifier = Modifier.align(Alignment.TopStart),
		)
	}
}

/**
 * Compose chapter renderer for the non-paginated document path. It preserves source text while
 * displaying partial translations and block images supplied by the reader state owner.
 */
@Composable
fun ComposeNovelChapter(
	content: String,
	settings: NovelReaderSettings,
	translation: NovelChapterTranslation?,
	imageModel: (String) -> Any?,
	imageContext: NovelComposeImageContext? = null,
	onImageClick: ((String) -> Unit)? = null,
	onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)? = null,
	listState: LazyListState? = null,
	modifier: Modifier = Modifier,
) {
	val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
	val contentColor = Color(palette.textColor)
	val blocks = androidx.compose.runtime.remember(content, translation) {
		buildNovelComposeDocument(content, translation)
	}
	val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
	val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
	var viewport = androidx.compose.runtime.remember { IntSize.Zero }
	val tapModifier = if (onTap == null) Modifier else Modifier
		.onSizeChanged { viewport = it }
		.pointerInput(onTap) {
			detectTapGestures { offset -> onTap(offset.x, offset.y, viewport) }
		}
	LazyColumn(
		state = listState ?: rememberLazyListState(),
		modifier = modifier
			.fillMaxSize()
			.background(Color(palette.backgroundColor))
			.then(tapModifier),
		contentPadding = PaddingValues(
			horizontal = settings.marginHorizontal.dp,
			vertical = settings.marginVertical.dp,
		),
		verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacing.dp),
	) {
		items(count = blocks.size, key = { index ->
			when (val block = blocks[index]) {
				is NovelComposeBlock.Image -> block.key
				is NovelComposeBlock.Text -> block.key
			}
		}) { index ->
			when (val block = blocks[index]) {
				is NovelComposeBlock.Image -> NovelComposeImage(
					path = block.path,
					imageModel = imageModel,
					imageContext = imageContext,
					onClick = onImageClick,
				)

				is NovelComposeBlock.Text -> {
					val style = MaterialTheme.typography.bodyLarge.copy(
						fontSize = settings.fontSizeSp.sp,
						lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
						textDirection = direction,
						color = contentColor,
					)
					if (block.translation == null) {
						NovelTextWithImageBlocks(
							text = block.original,
							inlineImages = block.inlineImages,
							imageModel = imageModel,
							imageContext = imageContext,
							onImageClick = onImageClick,
							style = style,
							textAlign = alignment,
						)
					} else if (block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) {
						Text(
							text = block.translation,
							style = style,
							textAlign = alignment,
						)
					} else {
						NovelTextWithImageBlocks(
							text = block.original,
							inlineImages = block.inlineImages,
							imageModel = imageModel,
							imageContext = imageContext,
							onImageClick = onImageClick,
							style = style.copy(fontSize = (settings.fontSizeSp * 0.86f).sp),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textAlign = alignment,
						)
						Text(
							text = block.translation,
							style = style,
							textAlign = alignment,
							modifier = Modifier.padding(top = 4.dp),
						)
					}
				}
			}
		}
	}
}

private data class NovelComposeWindowBlock(
	val chapter: NovelComposeChapterContent,
	val block: NovelComposeBlock,
)

@Composable
private fun ComposeNovelChapterWindow(
	chapters: List<NovelComposeChapterContent>,
	settings: NovelReaderSettings,
	imageModel: (String) -> Any?,
	onImageClick: ((String) -> Unit)?,
	onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)?,
	listState: LazyListState,
	modifier: Modifier,
	onVisibleChapterChanged: (Int) -> Unit,
	onRequestPreviousChapter: () -> Unit,
	onRequestNextChapter: () -> Unit,
	onVisibleProgress: (chapterIndex: Int, blockIndex: Int, blockCount: Int) -> Unit,
	ttsHighlightRange: IntRange?,
) {
	val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
	val contentColor = Color(palette.textColor)
	val blocks = androidx.compose.runtime.remember(chapters) {
		chapters.flatMap { chapter ->
			buildNovelComposeDocument(chapter.content, chapter.translation).map { block ->
				NovelComposeWindowBlock(chapter, block)
			}
		}
	}
	val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
	val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
	var viewport = androidx.compose.runtime.remember { IntSize.Zero }
	val tapModifier = if (onTap == null) Modifier else Modifier
		.onSizeChanged { viewport = it }
		.pointerInput(onTap) {
			detectTapGestures { offset -> onTap(offset.x, offset.y, viewport) }
		}
	LazyColumn(
		state = listState,
		modifier = modifier
			.fillMaxSize()
			.background(Color(palette.backgroundColor))
			.then(tapModifier),
		contentPadding = PaddingValues(
			horizontal = settings.marginHorizontal.dp,
			vertical = settings.marginVertical.dp,
		),
		verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacing.dp),
	) {
		items(
			count = blocks.size,
			key = { index ->
				val item = blocks[index]
				val blockKey = when (val block = item.block) {
					is NovelComposeBlock.Image -> block.key
					is NovelComposeBlock.Text -> block.key
				}
				"${item.chapter.chapterIndex}:$blockKey"
			},
		) { index ->
			val item = blocks[index]
			when (val block = item.block) {
				is NovelComposeBlock.Image -> NovelComposeImage(
					path = block.path,
					imageModel = imageModel,
					imageContext = item.chapter.imageContext,
					onClick = onImageClick,
				)

				is NovelComposeBlock.Text -> {
					val style = MaterialTheme.typography.bodyLarge.copy(
						fontSize = settings.fontSizeSp.sp,
						lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
						textDirection = direction,
						color = contentColor,
					)
					if (block.translation == null) {
						if (block.inlineImages.isEmpty()) Text(
							text = highlightedNovelText(
								text = block.original,
								sourceRange = block.sourceRange,
								highlightRange = ttsHighlightRange,
								highlightColor = MaterialTheme.colorScheme.secondaryContainer,
							),
							style = style,
							textAlign = alignment,
						) else NovelTextWithImageBlocks(
							text = block.original,
							inlineImages = block.inlineImages,
							imageModel = imageModel,
							imageContext = item.chapter.imageContext,
							onImageClick = onImageClick,
							style = style,
							textAlign = alignment,
						)
					} else if (block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) {
						Text(text = block.translation, style = style, textAlign = alignment)
					} else {
						NovelTextWithImageBlocks(
							text = block.original,
							inlineImages = block.inlineImages,
							imageModel = imageModel,
							imageContext = item.chapter.imageContext,
							onImageClick = onImageClick,
							style = style.copy(fontSize = (settings.fontSizeSp * 0.86f).sp),
							textAlign = alignment,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Text(
							text = block.translation,
							style = style,
							textAlign = alignment,
							modifier = Modifier.padding(top = 4.dp),
						)
					}
				}
			}
		}
	}
	LaunchedEffect(listState, blocks) {
		snapshotFlow {
			val info = listState.layoutInfo
			Triple(
				info.visibleItemsInfo.firstOrNull()?.index ?: -1,
				info.visibleItemsInfo.lastOrNull()?.index ?: -1,
				info.totalItemsCount,
			)
		}.distinctUntilChanged().collect { (first, last, total) ->
			if (first in blocks.indices) {
				val visibleChapter = blocks[first].chapter
				val chapterStart = blocks.indexOfFirst {
					it.chapter.chapterIndex == visibleChapter.chapterIndex
				}.coerceAtLeast(0)
				val chapterBlockCount = blocks.count {
					it.chapter.chapterIndex == visibleChapter.chapterIndex
				}.coerceAtLeast(1)
				onVisibleChapterChanged(visibleChapter.chapterIndex)
				onVisibleProgress(
					visibleChapter.chapterIndex,
					(first - chapterStart).coerceAtLeast(0),
					chapterBlockCount,
				)
			}
			if (first in 0..2) onRequestPreviousChapter()
			if (total > 0 && last >= total - 3) onRequestNextChapter()
		}
	}
}

/** Compose route bound to the Activity-retained novel state. */
@Composable
fun ComposeNovelReaderRoute(
	viewModel: NovelComposeReaderViewModel,
	imageModel: (String) -> Any?,
	onSettingsChanged: (NovelReaderSettings) -> Unit = {},
	onBookmark: () -> Unit = {},
	onTts: () -> Unit = {},
	onClearTranslationCache: () -> Unit = {},
	onChapterSelected: (Int) -> Unit = {},
	onModalDismissed: () -> Unit = {},
	onTtsPrevious: () -> Unit = {},
	onTtsPlayPause: () -> Unit = {},
	onTtsNext: () -> Unit = {},
	onTtsVoice: () -> Unit = {},
	onTtsClose: () -> Unit = {},
	onRequestPreviousChapter: () -> Unit = {},
	onRequestNextChapter: () -> Unit = {},
	onVisibleChapterChanged: (Int) -> Unit = {},
	onVisibleProgress: (chapterIndex: Int, blockIndex: Int, blockCount: Int) -> Unit = { _, _, _ -> },
	onPagedPositionChanged: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
	renderContent: Boolean = true,
	onImageClick: ((String) -> Unit)? = null,
	onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val settings = state.settings
	if (renderContent && settings != null && state.content.isNotBlank()) {
		val blocks = androidx.compose.runtime.remember(state.content, state.translation) {
			buildNovelComposeDocument(state.content, state.translation)
		}
		key(if (settings.readingMode == ReadingMode.SCROLL) "continuous" else state.chapterIndex) {
			val listState = rememberLazyListState(
				initialFirstVisibleItemIndex = state.scrollPosition
					?.firstVisibleBlock
					?.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
					?: 0,
				initialFirstVisibleItemScrollOffset = state.scrollPosition?.firstVisibleBlockOffsetPx ?: 0,
			)
			LaunchedEffect(listState, state.chapterIndex) {
				snapshotFlow {
					NovelComposeScrollPosition(
						firstVisibleBlock = listState.firstVisibleItemIndex,
						firstVisibleBlockOffsetPx = listState.firstVisibleItemScrollOffset,
					)
				}.distinctUntilChanged().collect(viewModel::publishScrollPosition)
			}
			LaunchedEffect(state.scrollRequest?.id, listState) {
				val request = state.scrollRequest ?: return@LaunchedEffect
				request.blockIndex?.let { blockIndex ->
					listState.animateScrollToItem(blockIndex)
				} ?: run {
					val viewportHeight = listState.layoutInfo.viewportSize.height
					if (viewportHeight > 0) {
						listState.animateScrollBy(viewportHeight * 0.88f * request.deltaPages)
					}
				}
			}
			if (settings.readingMode == ReadingMode.SCROLL && state.continuousChapters.isNotEmpty()) {
				ComposeNovelChapterWindow(
					chapters = state.continuousChapters,
					settings = settings,
					imageModel = imageModel,
					onImageClick = onImageClick,
					onTap = onTap,
					listState = listState,
					modifier = modifier,
					onVisibleChapterChanged = {
						viewModel.focusContinuousChapter(it)
						onVisibleChapterChanged(it)
					},
					onRequestPreviousChapter = onRequestPreviousChapter,
					onRequestNextChapter = onRequestNextChapter,
					onVisibleProgress = onVisibleProgress,
					ttsHighlightRange = state.ttsHighlightRange,
				)
			} else if (settings.readingMode == ReadingMode.PAGED) {
				ComposeNovelPagedChapter(
					state = state,
					settings = settings,
					imageModel = imageModel,
					onImageClick = onImageClick,
					onTap = onTap,
					onBookmark = onBookmark,
					onRequestPreviousChapter = onRequestPreviousChapter,
					onRequestNextChapter = onRequestNextChapter,
					onPositionChanged = { page, pageCount, charStart, charEnd, text ->
						viewModel.publishPagedPosition(page, pageCount, charStart, charEnd, text)
						onPagedPositionChanged(page, pageCount)
					},
					modifier = modifier,
				)
			} else {
				ComposeNovelChapter(
					content = state.content,
					settings = settings,
					translation = state.translation,
					imageModel = imageModel,
					imageContext = state.imageContext,
					onImageClick = onImageClick,
					onTap = onTap,
					listState = listState,
					modifier = modifier,
				)
			}
		}
	}
	NovelReaderOverlay(
		loading = state.loading,
		message = state.message,
		onMessageExpired = viewModel::dismissMessage,
		ttsVisible = state.ttsControlsVisible && !state.chromeEnabled,
		ttsState = state.ttsState,
		onTtsPrevious = onTtsPrevious,
		onTtsPlayPause = onTtsPlayPause,
		onTtsNext = onTtsNext,
		onTtsVoice = onTtsVoice,
		onTtsClose = onTtsClose,
	)
	if (!state.chromeEnabled && state.settingsSheetVisible && settings != null) {
		ComposeNovelReaderOptionsSheet(
			settings = settings,
			onDismiss = {
				viewModel.dismissSettings()
				onModalDismissed()
			},
			onSettingsChanged = {
				viewModel.publishSettings(it)
				onSettingsChanged(it)
			},
			onBookmark = onBookmark,
			onTts = onTts,
			onClearTranslationCache = onClearTranslationCache,
		)
	}
	if (!state.chromeEnabled && state.chaptersSheetVisible) {
		ComposeNovelChaptersSheet(
			chapters = state.chapters,
			currentIndex = state.currentChapterIndex,
			onDismiss = {
				viewModel.dismissChapters()
				onModalDismissed()
			},
			onChapterSelected = {
				viewModel.dismissChapters()
				onModalDismissed()
				onChapterSelected(it)
			},
		)
	}
}

private sealed interface NovelComposePagedElement {
	data class Text(val value: String, val sourceStart: Int) : NovelComposePagedElement
	data class Image(val path: String, val sourcePosition: Int) : NovelComposePagedElement
}

private sealed interface NovelComposePage {
	val charStart: Int
	val charEnd: Int

	data class Text(
		val value: String,
		override val charStart: Int,
		override val charEnd: Int,
	) : NovelComposePage

	data class Image(
		val path: String,
		override val charStart: Int,
		override val charEnd: Int,
	) : NovelComposePage
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeNovelPagedChapter(
	state: NovelComposeReaderUiState,
	settings: NovelReaderSettings,
	imageModel: (String) -> Any?,
	onImageClick: ((String) -> Unit)?,
	onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)?,
	onBookmark: () -> Unit,
	onRequestPreviousChapter: () -> Unit,
	onRequestNextChapter: () -> Unit,
	onPositionChanged: (Int, Int, Int, Int, String) -> Unit,
	modifier: Modifier,
) {
	val density = LocalDensity.current
	val textMeasurer = rememberTextMeasurer()
	val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
	val textColor = Color(palette.textColor)
	val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
	val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
	val style = MaterialTheme.typography.bodyLarge.copy(
		fontSize = settings.fontSizeSp.sp,
		lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
		textDirection = direction,
		color = textColor,
	)
	val coroutineScope = rememberCoroutineScope()
	var pullOffsetPx by remember(state.chapterId) { mutableFloatStateOf(0f) }
	val bookmarkThresholdPx = with(density) { 104.dp.toPx() }
	val maximumPullPx = with(density) { 138.dp.toPx() }
	val pullLabelEdgeOffsetPx = with(density) { 34.dp.toPx() }
	val bookmarkArmed = pullOffsetPx >= bookmarkThresholdPx
	fun settlePull(addBookmark: Boolean) {
		if (addBookmark) onBookmark()
		val startOffset = pullOffsetPx
		coroutineScope.launch {
			Animatable(startOffset).animateTo(
				targetValue = 0f,
				animationSpec = spring(
					dampingRatio = 0.78f,
					stiffness = 420f,
				),
			) {
				pullOffsetPx = value
			}
		}
	}
	val pullToBookmarkModifier = Modifier.pointerInput(state.chapterId, bookmarkThresholdPx) {
		detectVerticalDragGestures(
			onVerticalDrag = { change, dragAmount ->
				if (dragAmount > 0f || pullOffsetPx > 0f) {
					change.consume()
					pullOffsetPx = (pullOffsetPx + dragAmount).coerceIn(0f, maximumPullPx)
				}
			},
			onDragEnd = { settlePull(pullOffsetPx >= bookmarkThresholdPx) },
			onDragCancel = { settlePull(addBookmark = false) },
		)
	}
	var viewport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
	val tapModifier = if (onTap == null) {
		Modifier
	} else {
		Modifier
			.onSizeChanged { viewport = it }
			.pointerInput(onTap) {
				detectTapGestures { offset -> onTap(offset.x, offset.y, viewport) }
			}
	}
	BoxWithConstraints(
		modifier = modifier
			.fillMaxSize()
			.background(Color(palette.backgroundColor))
			.then(tapModifier),
	) {
		val contentWidthPx = with(density) {
			(maxWidth - settings.marginHorizontal.dp * 2).coerceAtLeast(1.dp).roundToPx()
		}
		val contentHeightPx = with(density) {
			(maxHeight - settings.marginVertical.dp * 2).coerceAtLeast(1.dp).roundToPx()
		}
		val pages = androidx.compose.runtime.remember(
			state.content,
			state.translation,
			settings,
			contentWidthPx,
			contentHeightPx,
		) {
			paginateNovelComposeDocument(
				blocks = buildNovelComposeDocument(state.content, state.translation),
				textMeasurer = textMeasurer,
				style = style,
				widthPx = contentWidthPx,
				heightPx = contentHeightPx,
			)
		}
		if (pages.isEmpty()) return@BoxWithConstraints
		val initialPage = state.position
			?.page
			?.coerceIn(pages.indices)
			?: 0
		val pagerState = rememberPagerState(
			initialPage = initialPage,
			pageCount = pages::size,
		)
		val boundarySwipeThresholdPx = with(density) { 48.dp.toPx() }
		val boundarySwipeConnection = androidx.compose.runtime.remember(
			pagerState,
			pages.size,
			state.chapterId,
		) {
			object : NestedScrollConnection {
				private var accumulatedX = 0f

				override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
					if (source == NestedScrollSource.UserInput) {
						val atPreviousBoundary = pagerState.currentPage == 0 && available.x > 0f
						val atNextBoundary =
							pagerState.currentPage == pages.lastIndex && available.x < 0f
						if (atPreviousBoundary || atNextBoundary) {
							accumulatedX += available.x
						}
					}
					return Offset.Zero
				}

				override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
					when {
						accumulatedX >= boundarySwipeThresholdPx -> onRequestPreviousChapter()
						accumulatedX <= -boundarySwipeThresholdPx -> onRequestNextChapter()
					}
					accumulatedX = 0f
					return Velocity.Zero
				}
			}
		}
		LaunchedEffect(state.pageRequest?.id, pages.size) {
			val request = state.pageRequest ?: return@LaunchedEffect
			pagerState.animateScrollToPage(request.page.coerceIn(pages.indices))
		}
		LaunchedEffect(pagerState, pages) {
			snapshotFlow { pagerState.settledPage }
				.distinctUntilChanged()
				.collect { index ->
					when (val page = pages[index]) {
						is NovelComposePage.Text -> onPositionChanged(
							index,
							pages.size,
							page.charStart,
							page.charEnd,
							page.value,
						)
						is NovelComposePage.Image -> onPositionChanged(
							index,
							pages.size,
							page.charStart,
							page.charEnd,
							"",
						)
					}
				}
		}
		val dualPage = settings.enableDualPage && maxWidth >= 600.dp
		Box(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(end = 22.dp),
		) {
			Text(
				text = stringResource(
					if (bookmarkArmed) {
						R.string.novel_release_to_bookmark
					} else {
						R.string.novel_pull_to_bookmark
					},
				),
				style = MaterialTheme.typography.labelMedium,
				color = if (bookmarkArmed) {
					MaterialTheme.colorScheme.primary
				} else {
					Color(palette.secondaryTextColor)
				},
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(end = 42.dp)
					.graphicsLayer {
						translationY = pullOffsetPx - pullLabelEdgeOffsetPx
					},
			)
			val bookmarkColor = if (bookmarkArmed) {
				MaterialTheme.colorScheme.primary
			} else {
				Color(palette.secondaryTextColor).copy(alpha = 0.72f)
			}
			Canvas(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.size(width = 30.dp, height = 88.dp),
			) {
				val notchDepth = 14.dp.toPx()
				val bookmarkPath = Path().apply {
					moveTo(0f, 0f)
					lineTo(size.width, 0f)
					lineTo(size.width, size.height)
					lineTo(size.width / 2f, size.height - notchDepth)
					lineTo(0f, size.height)
					close()
				}
				drawPath(bookmarkPath, bookmarkColor)
			}
		}
		HorizontalPager(
			state = pagerState,
			pageSize = if (dualPage) PageSize.Fixed(maxWidth / 2) else PageSize.Fill,
			key = { index -> "${state.chapterId}:${pages[index].charStart}:$index" },
			modifier = Modifier
				.fillMaxSize()
				.background(Color(palette.backgroundColor))
				.nestedScroll(boundarySwipeConnection)
				.then(pullToBookmarkModifier)
				.graphicsLayer { translationY = pullOffsetPx },
		) { index ->
			when (val page = pages[index]) {
				is NovelComposePage.Text -> Box(
					modifier = Modifier
						.fillMaxSize()
						.padding(
							horizontal = settings.marginHorizontal.dp,
							vertical = settings.marginVertical.dp,
						),
				) {
					Text(
						text = page.value,
						style = style,
						textAlign = alignment,
						modifier = Modifier.fillMaxWidth(),
					)
				}
				is NovelComposePage.Image -> Box(
					contentAlignment = Alignment.Center,
					modifier = Modifier
						.fillMaxSize()
						.padding(
							horizontal = settings.marginHorizontal.dp,
							vertical = settings.marginVertical.dp,
						),
				) {
					NovelComposeImage(
						path = page.path,
						imageModel = imageModel,
						imageContext = state.imageContext,
						onClick = onImageClick,
					)
				}
			}
		}
	}
}

private fun paginateNovelComposeDocument(
	blocks: List<NovelComposeBlock>,
	textMeasurer: androidx.compose.ui.text.TextMeasurer,
	style: androidx.compose.ui.text.TextStyle,
	widthPx: Int,
	heightPx: Int,
): List<NovelComposePage> {
	if (widthPx <= 0 || heightPx <= 0) return emptyList()
	val elements = buildList {
		blocks.forEach { block ->
			when (block) {
				is NovelComposeBlock.Image -> add(
					NovelComposePagedElement.Image(block.path, 0),
				)
				is NovelComposeBlock.Text -> {
					val displayed = when {
						block.translation == null -> block.original
						block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY -> block.translation
						else -> "${block.original}\n\n${block.translation}"
					}
					if (displayed.isNotBlank()) {
						add(NovelComposePagedElement.Text(displayed, block.sourceRange?.first ?: 0))
					}
					block.inlineImages.values.forEach { path ->
						add(NovelComposePagedElement.Image(path, block.sourceRange?.last ?: 0))
					}
				}
			}
		}
	}
	val constraints = Constraints(maxWidth = widthPx, maxHeight = heightPx)
	fun fits(text: String): Boolean {
		if (text.isEmpty()) return true
		return !textMeasurer.measure(
			text = text,
			style = style,
			constraints = constraints,
		).didOverflowHeight
	}
	val pages = mutableListOf<NovelComposePage>()
	var current = ""
	var currentStart = 0
	var currentEnd = 0
	fun flushText() {
		if (current.isBlank()) {
			current = ""
			return
		}
		pages += NovelComposePage.Text(current.trimEnd(), currentStart, currentEnd)
		current = ""
	}
	elements.forEach { element ->
		when (element) {
			is NovelComposePagedElement.Image -> {
				flushText()
				pages += NovelComposePage.Image(
					path = element.path,
					charStart = element.sourcePosition,
					charEnd = element.sourcePosition,
				)
			}
			is NovelComposePagedElement.Text -> {
				var remaining = element.value
				var consumed = 0
				while (remaining.isNotEmpty()) {
					val separator = if (current.isEmpty()) "" else "\n\n"
					val candidate = current + separator + remaining
					if (fits(candidate)) {
						if (current.isEmpty()) currentStart = element.sourceStart + consumed
						current = candidate
						currentEnd = element.sourceStart + consumed + remaining.length
						remaining = ""
					} else if (current.isNotEmpty()) {
						flushText()
					} else {
						var low = 1
						var high = remaining.length
						var best = 1
						while (low <= high) {
							val middle = (low + high) ushr 1
							if (fits(remaining.substring(0, middle))) {
								best = middle
								low = middle + 1
							} else {
								high = middle - 1
							}
						}
						val breakAt = remaining.lastIndexOfAny(
							charArrayOf('\n', ' ', '。', '！', '？', '.', '!', '?'),
							startIndex = (best - 1).coerceAtLeast(0),
						).takeIf { it >= best / 2 }?.plus(1) ?: best
						currentStart = element.sourceStart + consumed
						currentEnd = currentStart + breakAt
						current = remaining.substring(0, breakAt)
						remaining = remaining.substring(breakAt)
						consumed += breakAt
						flushText()
					}
				}
			}
		}
	}
	flushText()
	return pages
}

private fun highlightedNovelText(
	text: String,
	sourceRange: IntRange?,
	highlightRange: IntRange?,
	highlightColor: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.text.AnnotatedString {
	if (sourceRange == null || highlightRange == null) return androidx.compose.ui.text.AnnotatedString(text)
	val start = maxOf(sourceRange.first, highlightRange.first)
	val end = minOf(sourceRange.last, highlightRange.last)
	if (start > end) return androidx.compose.ui.text.AnnotatedString(text)
	return buildAnnotatedString {
		append(text)
		addStyle(
			SpanStyle(background = highlightColor),
			start = start - sourceRange.first,
			end = end - sourceRange.first + 1,
		)
	}
}

@Composable
private fun NovelTextWithImageBlocks(
	text: String,
	inlineImages: Map<String, String>,
	imageModel: (String) -> Any?,
	imageContext: NovelComposeImageContext?,
	onImageClick: ((String) -> Unit)?,
	style: androidx.compose.ui.text.TextStyle,
	textAlign: TextAlign,
	modifier: Modifier = Modifier,
	color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
	val segments = androidx.compose.runtime.remember(text, inlineImages) {
		INLINE_IMAGE_TOKEN.split(text).flatMapIndexed { index, chunk ->
			buildList {
				if (chunk.isNotEmpty()) add(NovelTextSegment.Text(chunk))
				if (index < INLINE_IMAGE_TOKEN.findAll(text).count()) {
					val token = INLINE_IMAGE_TOKEN.findAll(text).elementAt(index).value
					inlineImages[token]?.let { add(NovelTextSegment.Image(it)) }
				}
			}
		}
	}
	Column(modifier = modifier) {
		segments.forEach { segment ->
			when (segment) {
				is NovelTextSegment.Image -> NovelComposeImage(
					path = segment.path,
					imageModel = imageModel,
					imageContext = imageContext,
					onClick = onImageClick,
				)

				is NovelTextSegment.Text -> Text(
					text = segment.value,
					style = style,
					color = color,
					textAlign = textAlign,
				)
			}
		}
	}
}

@Composable
private fun NovelComposeImage(
	path: String,
	imageModel: (String) -> Any?,
	imageContext: NovelComposeImageContext?,
	onClick: ((String) -> Unit)?,
) {
	val context = LocalContext.current
	val bitmap by produceState<android.graphics.Bitmap?>(
		initialValue = null,
		key1 = path,
		key2 = imageContext,
	) {
		value = imageContext?.let { image ->
			runCatching {
				NovelInlineImageLoader.loadBitmap(
					context = context,
					imageLoader = SingletonImageLoader.get(context),
					imagePath = path,
					source = null,
					epubFilePath = image.epubFilePath,
					chapterPath = image.chapterPath,
					headers = image.headers,
				)
			}.getOrNull()
		}
	}
	val clickModifier = if (onClick == null) Modifier else Modifier.clickable { onClick(path) }
	if (bitmap != null) {
		androidx.compose.foundation.Image(
			bitmap = bitmap!!.asImageBitmap(),
			contentDescription = null,
			modifier = clickModifier.fillMaxWidth(),
		)
	} else {
		AsyncImage(
			model = imageModel(path),
			contentDescription = null,
			modifier = clickModifier.fillMaxWidth(),
		)
	}
}

private sealed interface NovelTextSegment {
	data class Text(val value: String) : NovelTextSegment
	data class Image(val path: String) : NovelTextSegment
}

private val INLINE_IMAGE_TOKEN = Regex("\\[INLINE_IMAGE_\\d+]")

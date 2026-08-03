package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.skepsun.kototoro.reader.novel.NovelPage
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.TextDirection as NovelTextDirection
import org.skepsun.kototoro.image.ui.NovelInlineImageLoader
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.ui.compose.READER_PAGE_CAMERA_DISTANCE
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderSimulationPageShadow
import org.skepsun.kototoro.reader.ui.compose.composeReaderPageCurl
import org.skepsun.kototoro.reader.ui.compose.rememberComposeReaderPageCurlState
import org.skepsun.kototoro.reader.ui.compose.resolveComposeReaderPageTransform
import org.skepsun.kototoro.reader.ui.compose.resolvePageCurlUnfolding
import org.skepsun.kototoro.reader.ui.compose.trackComposeReaderPageCurl
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val NovelReadingStatusReservedHeight = 28.dp

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
			detectUnconsumedTapGestures { offset -> onTap(offset.x, offset.y, viewport) }
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
	val chapterBlockIndex: Int,
	val chapterBlockCount: Int,
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
			val chapterBlocks = buildNovelComposeDocument(chapter.content, chapter.translation)
			chapterBlocks.mapIndexed { index, block ->
				NovelComposeWindowBlock(
					chapter = chapter,
					block = block,
					chapterBlockIndex = index,
					chapterBlockCount = chapterBlocks.size,
				)
			}
		}
	}
	val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
	val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
	var viewport = androidx.compose.runtime.remember { IntSize.Zero }
	val tapModifier = if (onTap == null) Modifier else Modifier
		.onSizeChanged { viewport = it }
		.pointerInput(onTap) {
			detectUnconsumedTapGestures { offset -> onTap(offset.x, offset.y, viewport) }
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
				val visibleBlock = blocks[first]
				onVisibleChapterChanged(visibleChapter.chapterIndex)
				onVisibleProgress(
					visibleChapter.chapterIndex,
					visibleBlock.chapterBlockIndex,
					visibleBlock.chapterBlockCount.coerceAtLeast(1),
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
	onToggleTranslation: () -> Unit = {},
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
		if (settings.readingMode == ReadingMode.PAGED) {
			ComposeNovelPagedChapter(
				state = state,
				chapters = state.continuousChapters,
				settings = settings,
				imageModel = imageModel,
				onImageClick = onImageClick,
				onTap = onTap,
				onBookmark = onBookmark,
					onRequestPreviousChapter = onRequestPreviousChapter,
					onRequestNextChapter = onRequestNextChapter,
					onPageRequestConsumed = viewModel::consumePageRequest,
					onPositionChanged = { chapterIndex, page, pageCount, charStart, charEnd, text ->
					viewModel.focusContinuousChapter(chapterIndex)
					onVisibleChapterChanged(chapterIndex)
					viewModel.publishPagedPosition(page, pageCount, charStart, charEnd, text)
					onPagedPositionChanged(page, pageCount)
				},
				modifier = modifier,
			)
		} else {
			val blocks = androidx.compose.runtime.remember(state.content, state.translation) {
				buildNovelComposeDocument(state.content, state.translation)
			}
			key("continuous") {
			val listState = rememberLazyListState(
				initialFirstVisibleItemIndex = state.scrollPosition
					?.firstVisibleBlock
					?.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
					?: 0,
				initialFirstVisibleItemScrollOffset = state.scrollPosition?.firstVisibleBlockOffsetPx ?: 0,
			)
			LaunchedEffect(listState, state.chapterIndex) {
				snapshotFlow {
					if (listState.isScrollInProgress) {
						null
					} else {
						NovelComposeScrollPosition(
							firstVisibleBlock = listState.firstVisibleItemIndex,
							firstVisibleBlockOffsetPx = listState.firstVisibleItemScrollOffset,
						)
					}
				}.filterNotNull().distinctUntilChanged().collect(viewModel::publishScrollPosition)
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
	}
	NovelReaderOverlay(
		loading = state.loading,
		message = state.message,
		controlsVisible = state.controlsVisible,
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
			onToggleTranslation = onToggleTranslation,
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

private sealed interface NovelComposePage {
	val chapterId: Long
	val chapterIndex: Int
	val charStart: Int
	val charEnd: Int

	data class Text(
		val value: String,
		override val chapterId: Long,
		override val chapterIndex: Int,
		override val charStart: Int,
		override val charEnd: Int,
	) : NovelComposePage

	data class Image(
		val path: String,
		val sourceKey: String,
		override val chapterId: Long,
		override val chapterIndex: Int,
		override val charStart: Int,
		override val charEnd: Int,
	) : NovelComposePage
}

private data class NovelPaginationChapter(
	val chapterId: Long,
	val chapterIndex: Int,
	val content: String,
	val translation: NovelChapterTranslation?,
)

private data class NovelPaginationLayoutKey(
	val settings: NovelReaderSettings,
	val style: androidx.compose.ui.text.TextStyle,
	val widthPx: Int,
	val heightPx: Int,
)

private class NovelPaginationRequest(
	val layoutKey: NovelPaginationLayoutKey,
)

private data class NovelPaginationResult(
	val request: NovelPaginationRequest,
	val pages: List<NovelComposePage>,
)

private fun novelComposePageKey(page: NovelComposePage): String = when (page) {
	is NovelComposePage.Text -> "text:${page.chapterId}:${page.charStart}:${page.charEnd}"
	is NovelComposePage.Image -> novelComposeImagePageKey(page.chapterId, page.sourceKey)
}

internal fun novelComposeImagePageKey(chapterId: Long, sourceKey: String): String =
	"image:$chapterId:$sourceKey"

internal fun novelDualPageCurlOffset(
	pageOffset: Float,
	isScrollInProgress: Boolean,
	curlOnEnd: Boolean,
): Float? {
	if (!isScrollInProgress) return null
	return if (curlOnEnd) {
		pageOffset.takeIf { it in 0f..2f }?.minus(1f)
	} else {
		pageOffset.takeIf { it in -1f..1f }
	}
}

internal fun novelDualPageCurlOnEnd(horizontalDragFraction: Float, isReversed: Boolean): Boolean {
	val isForward = horizontalDragFraction <= 0f
	return isForward != isReversed
}

internal data class NovelPageIdentity(val chapterId: Long, val chapterIndex: Int)

internal fun resolveNovelPageRequest(
	request: NovelPageRequest,
	pages: List<NovelPageIdentity>,
): Int? {
	val chapterStart = pages.indexOfFirst {
		it.chapterId == request.chapterId && it.chapterIndex == request.chapterIndex
	}
	if (chapterStart < 0) return null
	val chapterEnd = pages.indexOfLast {
		it.chapterId == request.chapterId && it.chapterIndex == request.chapterIndex
	}
	return (chapterStart + request.page).coerceIn(chapterStart, chapterEnd)
}

internal fun splitNovelPageLineRanges(
	lineCount: Int,
	pageHeightPx: Int,
	lineTop: (Int) -> Float,
	lineBottom: (Int) -> Float,
): List<IntRange> {
	if (lineCount <= 0 || pageHeightPx <= 0) return emptyList()
	return buildList {
		var firstLine = 0
		while (firstLine < lineCount) {
			val pageTop = lineTop(firstLine)
			var lastLine = firstLine
			while (lastLine + 1 < lineCount && lineBottom(lastLine + 1) - pageTop <= pageHeightPx) {
				lastLine++
			}
			add(firstLine..lastLine)
			firstLine = lastLine + 1
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeNovelPagedChapter(
	state: NovelComposeReaderUiState,
	chapters: List<NovelComposeChapterContent>,
	settings: NovelReaderSettings,
	imageModel: (String) -> Any?,
	onImageClick: ((String) -> Unit)?,
	onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)?,
	onBookmark: () -> Unit,
	onRequestPreviousChapter: () -> Unit,
	onRequestNextChapter: () -> Unit,
	onPageRequestConsumed: (Long) -> Unit,
	onPositionChanged: (Int, Int, Int, Int, Int, String) -> Unit,
	modifier: Modifier,
) {
	val density = LocalDensity.current
	// Pagination owns its measurer and only uses it from its background calculation.
	val textMeasurer = rememberTextMeasurer(cacheSize = 0)
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
	fun settlePull(toggleBookmark: Boolean) {
		if (toggleBookmark) onBookmark()
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
		detectDownwardPullGestures(
			onPull = { dragAmount ->
				pullOffsetPx = (pullOffsetPx + dragAmount).coerceIn(0f, maximumPullPx)
			},
			onRelease = { settlePull(pullOffsetPx >= bookmarkThresholdPx) },
			onCancel = { settlePull(toggleBookmark = false) },
		)
	}
	var viewport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
	val tapModifier = if (onTap == null) {
		Modifier
	} else {
		Modifier
			.onSizeChanged { viewport = it }
			.pointerInput(onTap) {
				detectUnconsumedTapGestures { offset -> onTap(offset.x, offset.y, viewport) }
			}
	}
	BoxWithConstraints(
		modifier = modifier
			.fillMaxSize()
			.background(Color(palette.backgroundColor))
			.then(tapModifier),
	) {
		val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
		val contentWidthPx = with(density) {
			(maxWidth - settings.marginHorizontal.dp * 2).coerceAtLeast(1.dp).roundToPx()
		}
		val contentHeightPx = with(density) {
			(
				maxHeight - statusBarInset - settings.marginVertical.dp * 2 -
					if (settings.showReadingStatus) NovelReadingStatusReservedHeight else 0.dp
				).coerceAtLeast(1.dp).roundToPx()
		}
		val paginationChapters = androidx.compose.runtime.remember(chapters, state.chapterId, state.content, state.translation) {
			chapters.map { chapter ->
				NovelPaginationChapter(
					chapterId = chapter.chapterId,
					chapterIndex = chapter.chapterIndex,
					content = chapter.content,
					translation = chapter.translation,
				)
			}.ifEmpty {
				listOf(
					NovelPaginationChapter(
						chapterId = state.chapterId,
						chapterIndex = state.chapterIndex,
						content = state.content,
						translation = state.translation,
					),
				)
			}
		}
		val paginationRequest = androidx.compose.runtime.remember(
			paginationChapters,
			settings,
			contentWidthPx,
			contentHeightPx,
			style,
		) {
			NovelPaginationRequest(
				NovelPaginationLayoutKey(
					settings = settings,
					style = style,
					widthPx = contentWidthPx,
					heightPx = contentHeightPx,
				),
			)
		}
		var paginationResult by androidx.compose.runtime.remember {
			mutableStateOf<NovelPaginationResult?>(null)
		}
		LaunchedEffect(paginationRequest) {
			val pages = withContext(Dispatchers.Default) {
				paginateNovelComposeChapterWindow(
					chapters = paginationChapters,
					textMeasurer = textMeasurer,
					style = style,
					widthPx = contentWidthPx,
					heightPx = contentHeightPx,
				)
			}
			paginationResult = NovelPaginationResult(paginationRequest, pages)
			android.util.Log.d(
				NOVEL_PAGER_LOG_TAG,
				"pagination chapters=${paginationChapters.joinToString { "${it.chapterIndex}/${it.chapterId}" }} " +
					"pages=${pages.size}",
			)
		}
		val exactResult = paginationResult?.takeIf { it.request === paginationRequest }
		val compatibleResult = paginationResult?.takeIf { result ->
			result.request.layoutKey == paginationRequest.layoutKey &&
				result.pages.any {
					it.chapterId == state.chapterId && it.chapterIndex == state.chapterIndex
				}
		}
		val displayedResult = exactResult ?: compatibleResult
			val pages = displayedResult?.pages
			if (pages == null) {
				if (!state.loading) {
					KototoroLoadingIndicator(modifier = Modifier.align(Alignment.Center))
				}
				return@BoxWithConstraints
		}
		if (pages.isEmpty()) return@BoxWithConstraints
		val pagerState = rememberPagerState(
			pageCount = pages::size,
		)
		var settledPageKey by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
		var readyGeneration by remember { mutableStateOf<NovelPaginationRequest?>(null) }
		val displayedGeneration = displayedResult.request
		LaunchedEffect(displayedGeneration) {
			val pendingRequest = state.pageRequest
			if (pendingRequest != null && exactResult?.request === displayedGeneration) {
				return@LaunchedEffect
			}
			val anchor = settledPageKey
			if (anchor != null) {
				val targetPage = pages.indexOfFirst { novelComposePageKey(it) == anchor }
				if (targetPage >= 0 && targetPage != pagerState.currentPage) {
					pagerState.scrollToPage(targetPage)
				}
			}
			readyGeneration = displayedGeneration
		}
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
		LaunchedEffect(state.pageRequest?.id, exactResult?.request) {
			val request = state.pageRequest ?: return@LaunchedEffect
			val result = exactResult
			if (result == null) {
				android.util.Log.d(
					NOVEL_PAGER_LOG_TAG,
					"wait request id=${request.id} chapter=${request.chapterIndex}/${request.chapterId}",
				)
				return@LaunchedEffect
			}
			val targetPage = resolveNovelPageRequest(
				request = request,
				pages = result.pages.map { NovelPageIdentity(it.chapterId, it.chapterIndex) },
			)
			if (targetPage == null) {
				android.util.Log.w(
					NOVEL_PAGER_LOG_TAG,
					"unresolved request id=${request.id} chapter=${request.chapterIndex}/${request.chapterId} " +
						"pages=${result.pages.size}",
				)
				return@LaunchedEffect
			}
			android.util.Log.d(
				NOVEL_PAGER_LOG_TAG,
				"execute request id=${request.id} target=$targetPage pages=${result.pages.size}",
			)
			pagerState.scrollToPage(targetPage)
			readyGeneration = result.request
			onPageRequestConsumed(request.id)
		}
		LaunchedEffect(pagerState, displayedGeneration, readyGeneration) {
			if (readyGeneration !== displayedGeneration) return@LaunchedEffect
			snapshotFlow { pagerState.settledPage }
				.distinctUntilChanged()
				.collect { index ->
					val page = pages.getOrNull(index) ?: return@collect
					settledPageKey = novelComposePageKey(page)
					val chapterStart = pages.indexOfFirst { it.chapterId == page.chapterId }
					val chapterEnd = pages.indexOfLast { it.chapterId == page.chapterId }
					val hasPreviousChapter = pages.any { it.chapterIndex == page.chapterIndex - 1 }
					val hasNextChapter = pages.any { it.chapterIndex == page.chapterIndex + 1 }
					if (index <= chapterStart + 2 && !hasPreviousChapter) onRequestPreviousChapter()
					if (index >= chapterEnd - 2 && !hasNextChapter) onRequestNextChapter()
					val localPage = (index - chapterStart).coerceAtLeast(0)
					val localPageCount = (chapterEnd - chapterStart + 1).coerceAtLeast(1)
					android.util.Log.d(
						NOVEL_PAGER_LOG_TAG,
						"settled global=$index chapter=${page.chapterIndex}/${page.chapterId} " +
							"local=$localPage/$localPageCount window=${pages.size}",
					)
					when (page) {
						is NovelComposePage.Text -> onPositionChanged(
							page.chapterIndex,
							localPage,
							localPageCount,
							page.charStart,
							page.charEnd,
							page.value,
						)
						is NovelComposePage.Image -> onPositionChanged(
							page.chapterIndex,
							localPage,
							localPageCount,
							page.charStart,
							page.charEnd,
							"",
						)
					}
				}
		}
		val dualPage = settings.enableDualPage && maxWidth >= 600.dp
		val pageCurlState = rememberComposeReaderPageCurlState()
		LaunchedEffect(pagerState.isScrollInProgress) {
			if (!pagerState.isScrollInProgress) pageCurlState.resetDrag()
		}
		val isSimulationCurlUnfolding = resolvePageCurlUnfolding(
			settledPage = pagerState.settledPage,
			targetPage = pagerState.targetPage,
			horizontalDragFraction = pageCurlState.horizontalDragFraction,
			isReadingReversed = false,
		)
			HorizontalPager(
			state = pagerState,
			pageSize = if (dualPage) PageSize.Fixed(maxWidth / 2) else PageSize.Fill,
			beyondViewportPageCount = 1,
			userScrollEnabled = readyGeneration === displayedGeneration,
			key = { index -> novelComposePageKey(pages[index]) },
			modifier = Modifier
				.fillMaxSize()
				.background(Color(palette.backgroundColor))
				.nestedScroll(boundarySwipeConnection)
				.then(pullToBookmarkModifier)
				.trackComposeReaderPageCurl(pageCurlState, settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION)
				.graphicsLayer { translationY = pullOffsetPx },
		) { index ->
			val page = pages[index]
			val isSimulation = settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION
			val pageOffset = (index - pagerState.currentPage) - pagerState.currentPageOffsetFraction
			val curlOnEnd = !dualPage || novelDualPageCurlOnEnd(
				horizontalDragFraction = pageCurlState.horizontalDragFraction,
				isReversed = false,
			)
			val simulationOffset = if (isSimulation && dualPage) {
				novelDualPageCurlOffset(
					pageOffset = pageOffset,
					isScrollInProgress = pagerState.isScrollInProgress,
					curlOnEnd = curlOnEnd,
				)
			} else if (isSimulation) {
				pageOffset
			} else {
				null
			}
			val simulationTransform = simulationOffset?.let { offset ->
				resolveComposeReaderPageTransform(
					animation = ReaderAnimation.SIMULATION,
					pageOffset = offset,
					isVertical = false,
					isReversed = dualPage && !curlOnEnd,
					isCurlUnfolding = isSimulationCurlUnfolding,
				)
			}
			val pageModifier = if (simulationTransform != null) {
				Modifier
					.zIndex(simulationTransform.zIndex)
					.graphicsLayer {
						alpha = simulationTransform.alpha
						translationX = simulationTransform.translationFactor * size.width
						rotationY = simulationTransform.rotationY
						transformOrigin = simulationTransform.transformOrigin
						cameraDistance = READER_PAGE_CAMERA_DISTANCE
					}
			} else {
				Modifier
			}
			Box(
				modifier = pageModifier
					.fillMaxSize()
					.then(
						if (simulationTransform != null) {
							Modifier.composeReaderPageCurl(
								transform = simulationTransform,
								isVertical = false,
								isReadingReversed = false,
								state = pageCurlState,
							)
						} else {
							Modifier
						},
					)
					.background(Color(palette.backgroundColor)),
			) {
				when (page) {
					is NovelComposePage.Text -> Box(
						modifier = Modifier
							.fillMaxSize()
							.padding(
								start = settings.marginHorizontal.dp,
								top = statusBarInset + settings.marginVertical.dp,
								end = settings.marginHorizontal.dp,
								bottom = settings.marginVertical.dp +
									if (settings.showReadingStatus) NovelReadingStatusReservedHeight else 0.dp,
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
								start = settings.marginHorizontal.dp,
								top = statusBarInset + settings.marginVertical.dp,
								end = settings.marginHorizontal.dp,
								bottom = settings.marginVertical.dp +
									if (settings.showReadingStatus) NovelReadingStatusReservedHeight else 0.dp,
							),
					) {
						NovelComposeImage(
							path = page.path,
							imageModel = imageModel,
							imageContext = chapters.firstOrNull {
								it.chapterIndex == page.chapterIndex
							}?.imageContext ?: state.imageContext,
							onClick = onImageClick,
						)
					}
				}
				if (settings.showReadingStatus) {
					val chapterStart = pages.indexOfFirst { it.chapterId == page.chapterId }
					val chapterEnd = pages.indexOfLast { it.chapterId == page.chapterId }
					val localPage = (index - chapterStart).coerceAtLeast(0)
					val localPageCount = (chapterEnd - chapterStart + 1).coerceAtLeast(1)
					val chapterTitle = chapters.firstOrNull {
						it.chapterId == page.chapterId && it.chapterIndex == page.chapterIndex
					}?.chapterTitle.orEmpty().ifBlank {
						state.chapterTitle.takeIf { page.chapterId == state.chapterId }.orEmpty()
					}
					AnimatedVisibility(
						visible = !state.controlsVisible,
						enter = fadeIn(),
						exit = fadeOut(),
						modifier = Modifier.align(Alignment.BottomCenter),
					) {
						NovelPageReadingStatus(
							chapterTitle = chapterTitle,
							progressLabel = "${localPage + 1} / $localPageCount",
							settings = settings,
						)
					}
				}
				if (simulationTransform != null) {
					ComposeReaderSimulationPageShadow(
						transform = simulationTransform,
					)
					}
				}
			}
			if (pullOffsetPx > 0f) {
				NovelPullBookmarkIndicator(
					bookmarked = state.isCurrentPageBookmarked,
					armed = bookmarkArmed,
					contentColor = Color(palette.secondaryTextColor),
					modifier = Modifier
						.align(Alignment.TopCenter)
						.graphicsLayer {
							translationY = pullOffsetPx - pullLabelEdgeOffsetPx
						},
				)
			}
		}
	}

@Composable
private fun NovelPullBookmarkIndicator(
	bookmarked: Boolean,
	armed: Boolean,
	contentColor: Color,
	modifier: Modifier = Modifier,
) {
	val resultingBookmarkState = bookmarked.xor(armed)
	val label = when {
		bookmarked && armed -> R.string.novel_release_to_remove_bookmark
		bookmarked -> R.string.novel_pull_to_remove_bookmark
		armed -> R.string.novel_release_to_bookmark
		else -> R.string.novel_pull_to_bookmark
	}
	val color = if (armed) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.78f)
	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
	) {
		Icon(
			painter = painterResource(
				if (resultingBookmarkState) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark,
			),
			contentDescription = null,
			tint = color,
			modifier = Modifier.size(24.dp),
		)
		Text(
			text = stringResource(label),
			style = MaterialTheme.typography.labelMedium,
			color = color,
		)
	}
}

@Composable
private fun NovelPageReadingStatus(
	chapterTitle: String,
	progressLabel: String,
	settings: NovelReaderSettings,
) {
	val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
	val contentColor = Color(palette.chromeTextColor).copy(alpha = 0.78f)
	val backgroundColor = if (settings.isReadingStatusTransparent) {
		Color.Transparent
	} else {
		Color(palette.chromeBackgroundColor).copy(alpha = 0.72f)
	}
	Surface(
		color = backgroundColor,
		contentColor = contentColor,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
		) {
			Text(
				text = chapterTitle,
				style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = progressLabel,
				style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
				modifier = Modifier.padding(start = 10.dp),
			)
		}
	}
}

private suspend fun paginateNovelComposeDocument(
	blocks: List<NovelComposeBlock>,
	chapterId: Long,
	chapterIndex: Int,
	textMeasurer: androidx.compose.ui.text.TextMeasurer,
	style: androidx.compose.ui.text.TextStyle,
	widthPx: Int,
	heightPx: Int,
): List<NovelComposePage> {
	if (widthPx <= 0 || heightPx <= 0) return emptyList()
	val pages = mutableListOf<NovelComposePage>()
	val textBatch = StringBuilder()
	var textBatchSourceStart = 0

	suspend fun flushTextBatch() {
		if (textBatch.isEmpty()) return
		val text = textBatch.toString()
		textBatch.clear()
		currentCoroutineContext().ensureActive()
		val layout = textMeasurer.measure(
			text = text,
			style = style,
			constraints = Constraints(maxWidth = widthPx),
		)
		val lineRanges = splitNovelPageLineRanges(
			lineCount = layout.lineCount,
			pageHeightPx = heightPx,
			lineTop = layout::getLineTop,
			lineBottom = layout::getLineBottom,
		)
		for (lineRange in lineRanges) {
			currentCoroutineContext().ensureActive()
			val start = layout.getLineStart(lineRange.first)
			val end = layout.getLineEnd(lineRange.last)
			val value = text.substring(start, end).trimEnd()
			if (value.isNotBlank()) {
				pages += NovelComposePage.Text(
					value = value,
					chapterId = chapterId,
					chapterIndex = chapterIndex,
					charStart = textBatchSourceStart + start,
					charEnd = textBatchSourceStart + end,
				)
			}
		}
	}

	for (block in blocks) {
		currentCoroutineContext().ensureActive()
		when (block) {
			is NovelComposeBlock.Image -> {
				flushTextBatch()
				pages += NovelComposePage.Image(
					path = block.path,
					sourceKey = block.key,
					chapterId = chapterId,
					chapterIndex = chapterIndex,
					charStart = 0,
					charEnd = 0,
				)
			}
			is NovelComposeBlock.Text -> {
				val displayed = when {
					block.translation == null -> block.original
					block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY -> block.translation
					else -> "${block.original}\n\n${block.translation}"
				}
				if (displayed.isNotBlank()) {
					if (textBatch.isNotEmpty()) {
						textBatch.append("\n\n")
					} else {
						textBatchSourceStart = block.sourceRange?.first ?: 0
					}
					textBatch.append(displayed)
				}
				if (block.inlineImages.isNotEmpty()) {
					flushTextBatch()
					block.inlineImages.forEach { (token, path) ->
						pages += NovelComposePage.Image(
							path = path,
							sourceKey = "${block.key}:$token",
							chapterId = chapterId,
							chapterIndex = chapterIndex,
							charStart = block.sourceRange?.last ?: 0,
							charEnd = block.sourceRange?.last ?: 0,
						)
					}
				}
			}
		}
	}
	flushTextBatch()
	return pages
}

private suspend fun paginateNovelComposeChapterWindow(
	chapters: List<NovelPaginationChapter>,
	textMeasurer: androidx.compose.ui.text.TextMeasurer,
	style: androidx.compose.ui.text.TextStyle,
	widthPx: Int,
	heightPx: Int,
): List<NovelComposePage> {
	return buildList {
		for (chapter in chapters) {
			currentCoroutineContext().ensureActive()
			addAll(
				paginateNovelComposeDocument(
					blocks = buildNovelComposeDocument(chapter.content, chapter.translation),
					chapterId = chapter.chapterId,
					chapterIndex = chapter.chapterIndex,
					textMeasurer = textMeasurer,
					style = style,
					widthPx = widthPx,
					heightPx = heightPx,
				),
			)
		}
	}
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
	val clickModifier = if (onClick == null) {
		Modifier
	} else {
		Modifier.pointerInput(path, onClick) {
			detectImageLongPress { onClick(path) }
		}
	}
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

private suspend fun PointerInputScope.detectUnconsumedTapGestures(onTap: (Offset) -> Unit) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
		if (down.isConsumed) return@awaitEachGesture
		val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
		if (up != null && !up.isConsumed) onTap(up.position)
	}
}

private suspend fun PointerInputScope.detectDownwardPullGestures(
	onPull: (Float) -> Unit,
	onRelease: () -> Unit,
	onCancel: () -> Unit,
) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
		var pullLocked = false
		var released = false
		var previousY = down.position.y
		do {
			val event = awaitPointerEvent(PointerEventPass.Initial)
			val change = event.changes.firstOrNull { it.id == down.id } ?: break
			if (!change.pressed) {
				if (pullLocked) {
					change.consume()
					onRelease()
					released = true
				}
				break
			}
			if (event.changes.count { it.pressed } > 1) {
				if (pullLocked) event.changes.forEach { it.consume() }
				break
			}
			if (pullLocked) {
				val dragAmount = change.position.y - previousY
				previousY = change.position.y
				change.consume()
				onPull(dragAmount)
				continue
			}

			val totalX = change.position.x - down.position.x
			val totalY = change.position.y - down.position.y
			if (abs(totalX) <= viewConfiguration.touchSlop && abs(totalY) <= viewConfiguration.touchSlop) {
				continue
			}
			if (totalY > viewConfiguration.touchSlop && totalY > abs(totalX)) {
				pullLocked = true
				previousY = change.position.y
				change.consume()
				onPull(totalY)
			} else {
				break
			}
		} while (true)
		if (pullLocked && !released) onCancel()
	}
}

private suspend fun PointerInputScope.detectImageLongPress(onLongPress: () -> Unit) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		if (awaitLongPressOrCancellation(down.id) == null) return@awaitEachGesture
		onLongPress()
		do {
			val event = awaitPointerEvent(PointerEventPass.Main)
			event.changes.forEach { it.consume() }
		} while (event.changes.any { it.pressed })
	}
}

private sealed interface NovelTextSegment {
	data class Text(val value: String) : NovelTextSegment
	data class Image(val path: String) : NovelTextSegment
}

private val INLINE_IMAGE_TOKEN = Regex("\\[INLINE_IMAGE_\\d+]")

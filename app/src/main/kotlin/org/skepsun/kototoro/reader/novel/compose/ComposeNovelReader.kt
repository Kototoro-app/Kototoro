package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import org.skepsun.kototoro.reader.novel.NovelPage
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.TextDirection as NovelTextDirection
import org.skepsun.kototoro.image.ui.NovelInlineImageLoader

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
		modifier = modifier.fillMaxSize().then(tapModifier),
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
		modifier = modifier.fillMaxSize().then(tapModifier),
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
				onVisibleChapterChanged(blocks[first].chapter.chapterIndex)
				onVisibleProgress(blocks[first].chapter.chapterIndex, first, total)
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
	NovelReaderOverlay(
		loading = state.loading,
		message = state.message,
		onMessageExpired = viewModel::dismissMessage,
		ttsVisible = state.ttsControlsVisible,
		ttsState = state.ttsState,
		onTtsPrevious = onTtsPrevious,
		onTtsPlayPause = onTtsPlayPause,
		onTtsNext = onTtsNext,
		onTtsVoice = onTtsVoice,
		onTtsClose = onTtsClose,
	)
	if (state.settingsSheetVisible && settings != null) {
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
	if (state.chaptersSheetVisible) {
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

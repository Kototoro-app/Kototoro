package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.delay
import org.skepsun.kototoro.reader.novel.NovelPage
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.TextDirection as NovelTextDirection
import org.skepsun.kototoro.image.ui.NovelInlineImageLoader
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroMotion
import org.skepsun.kototoro.reader.ui.compose.READER_PAGE_CAMERA_DISTANCE
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderSimulationPageShadow
import org.skepsun.kototoro.reader.ui.compose.composeReaderPageCurl
import org.skepsun.kototoro.reader.ui.compose.rememberComposeReaderPageCurlState
import org.skepsun.kototoro.reader.ui.compose.resolveComposeReaderPageTransform
import org.skepsun.kototoro.reader.ui.compose.resolvePageCurlUnfolding
import org.skepsun.kototoro.reader.ui.compose.trackComposeReaderPageCurl
import org.skepsun.kototoro.reader.ui.compose.whenReaderAnimationsEnabled
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val NovelReadingStatusReservedHeight = 28.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeNovelReader(
    pages: List<NovelPage>,
    settings: NovelReaderSettings,
    initialPage: Int,
    onPageChanged: (NovelPage) -> Unit,
    onTextSelectionChanged: (NovelTextSelection?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    val fontFamily = rememberNovelReaderFontFamily(settings.font)
    if (settings.readingMode == ReadingMode.SCROLL) {
        ComposeNovelContinuousReader(
            pages,
            settings,
            initialPage,
            fontFamily,
            onPageChanged,
            onTextSelectionChanged,
            modifier,
        )
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
            NovelPageText(
                page = pages[index],
                settings = settings,
                fontFamily = fontFamily,
                onSelectionChanged = onTextSelectionChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ComposeNovelContinuousReader(
    pages: List<NovelPage>,
    settings: NovelReaderSettings,
    initialPage: Int,
    fontFamily: FontFamily,
    onPageChanged: (NovelPage) -> Unit,
    onTextSelectionChanged: (NovelTextSelection?) -> Unit,
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
            NovelPageText(
                page = pages[index],
                settings = settings,
                fontFamily = fontFamily,
                onSelectionChanged = onTextSelectionChanged,
                modifier = Modifier.fillParentMaxWidth(),
            )
        }
    }
}

@Composable
private fun NovelPageText(
    page: NovelPage,
    settings: NovelReaderSettings,
    fontFamily: FontFamily,
    onSelectionChanged: (NovelTextSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontal = settings.marginHorizontal.dp
    val vertical = settings.marginVertical.dp
    val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
    val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
    Box(modifier = modifier.padding(PaddingValues(horizontal = horizontal, vertical = vertical))) {
        NovelSelectionContainer(
            chapterId = page.chapterId,
            chapterIndex = page.chapterIndex,
            chapterText = page.text,
            onSelectionChanged = onSelectionChanged,
            renderedText = page.text,
        ) {
            Text(
                text = formatNovelParagraphText(
                    text = page.text,
                    indentEnabled = settings.enableParagraphIndent,
                    spacingLines = settings.paragraphSpacingLines,
                ),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
                    textDirection = direction,
                ),
                textAlign = alignment,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/**
 * Compose chapter renderer for the non-paginated document path. It preserves source text while
 * displaying partial translations and block images supplied by the reader state owner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeNovelChapter(
    content: String,
    settings: NovelReaderSettings,
    translation: NovelChapterTranslation?,
    imageModel: (String) -> Any?,
    chapterId: Long = 0L,
    chapterIndex: Int = 0,
    novelMarkings: List<NovelMarkingEntity> = emptyList(),
    imageContext: NovelComposeImageContext? = null,
    onImageClick: ((String) -> Unit)? = null,
    onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onTextSelectionChanged: (NovelTextSelection?) -> Unit = {},
    onMarkingClick: (NovelMarkingEntity, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    textSelectionActive: Boolean = false,
    listState: LazyListState? = null,
    markingHighlightRange: IntRange? = null,
    markingHighlightText: String? = null,
    modifier: Modifier = Modifier,
) {
    val actualListState = listState ?: rememberLazyListState()
    val fontFamily = rememberNovelReaderFontFamily(settings.font)
    val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
    val contentColor = Color(palette.textColor)
    val blocks = androidx.compose.runtime.remember(content, translation) {
        buildNovelComposeDocument(content, translation)
    }
    LaunchedEffect(markingHighlightRange, markingHighlightText, blocks) {
        val range = markingHighlightRange
        val cleanText = markingHighlightText?.trim().orEmpty()
        if (range == null && cleanText.isEmpty()) return@LaunchedEffect
        val targetBlockIndex = blocks.indexOfFirst { item ->
            when (item) {
                is NovelComposeBlock.Text -> {
                    val textMatch = cleanText.isNotEmpty() && item.original.contains(cleanText)
                    val rangeMatch = range != null && item.sourceRange?.let {
                        range.first in it || it.first in range
                    } ?: false
                    textMatch || rangeMatch
                }
                else -> false
            }
        }
        if (targetBlockIndex >= 0) {
            actualListState.animateScrollToItem(targetBlockIndex)
        }
    }
    val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
    val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
    val paragraphSpacing = with(LocalDensity.current) {
        (settings.fontSizeSp * settings.lineSpacing * settings.paragraphSpacingLines).sp.toDp()
    }
    val statusBarInset = WindowInsets.statusBarsIgnoringVisibility
        .asPaddingValues()
        .calculateTopPadding()
    val gestureModifier = Modifier.novelReaderGestures(onTap, onLongPress, textSelectionActive)
    NovelSelectionContainer(
        chapterId = chapterId,
        chapterIndex = chapterIndex,
        chapterText = content,
        onSelectionChanged = onTextSelectionChanged,
    ) {
        LazyColumn(
            state = actualListState,
            modifier = modifier
                .fillMaxSize()
                .background(Color(palette.backgroundColor))
                .then(gestureModifier),
            contentPadding = PaddingValues(
                start = settings.marginHorizontal.dp,
                top = statusBarInset + settings.marginVertical.dp,
                end = settings.marginHorizontal.dp,
                bottom = settings.marginVertical.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(paragraphSpacing),
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
                        val original = formatNovelParagraphText(
                            text = block.original,
                            indentEnabled = settings.enableParagraphIndent,
                            spacingLines = settings.paragraphSpacingLines,
                        )
                        val translated = block.translation?.let {
                            formatNovelParagraphText(
                                text = it,
                                indentEnabled = settings.enableParagraphIndent,
                                spacingLines = settings.paragraphSpacingLines,
                            )
                        }
                        val style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = fontFamily,
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
                            textDirection = direction,
                            color = contentColor,
                        )
                        if (block.translation == null) {
                            if (block.inlineImages.isEmpty()) {
                                NovelMarkingText(
                                    text = highlightedNovelText(
                                        text = original,
                                        sourceRange = block.sourceRange,
                                        highlightRange = null,
                                        highlightColor = MaterialTheme.colorScheme.secondaryContainer,
                                        markings = novelMarkings.filter { it.chapterId == chapterId },
                                        transientHighlightRange = markingHighlightRange,
                                        transientHighlightText = markingHighlightText,
                                    ),
                                    style = style,
                                    textAlign = alignment,
                                    sourceRange = block.sourceRange,
                                    markings = novelMarkings.filter { it.chapterId == chapterId },
                                    onMarkingClick = onMarkingClick,
                                    textSelectionActive = textSelectionActive,
                                )
                            } else NovelTextWithImageBlocks(
                                text = original,
                                inlineImages = block.inlineImages,
                                imageModel = imageModel,
                                imageContext = imageContext,
                                onImageClick = onImageClick,
                                style = style,
                                textAlign = alignment,
                            )
                        } else if (block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) {
                            Text(
                                text = translated.orEmpty(),
                                style = style,
                                textAlign = alignment,
                            )
                        } else {
                            NovelTextWithImageBlocks(
                                text = original,
                                inlineImages = block.inlineImages,
                                imageModel = imageModel,
                                imageContext = imageContext,
                                onImageClick = onImageClick,
                                style = style.copy(fontSize = (settings.fontSizeSp * 0.86f).sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = alignment,
                            )
                            Text(
                                text = translated.orEmpty(),
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
}

private data class NovelComposeWindowBlock(
    val chapter: NovelComposeChapterContent,
    val block: NovelComposeBlock,
    val chapterBlockIndex: Int,
    val chapterBlockCount: Int,
)

private fun buildNovelComposeWindowBlocks(chapters: List<NovelComposeChapterContent>): List<NovelComposeWindowBlock> {
    return chapters.flatMap { chapter ->
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeNovelChapterWindow(
    chapters: List<NovelComposeChapterContent>,
    settings: NovelReaderSettings,
    imageModel: (String) -> Any?,
    onImageClick: ((String) -> Unit)?,
    onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)?,
    onLongPress: (() -> Unit)?,
    onTextSelectionChanged: (NovelTextSelection?) -> Unit,
    onMarkingClick: (NovelMarkingEntity, androidx.compose.ui.geometry.Rect) -> Unit,
    textSelectionActive: Boolean,
    novelMarkings: List<NovelMarkingEntity>,
    listState: LazyListState,
    modifier: Modifier,
    onVisibleChapterChanged: (Int) -> Unit,
    onRequestPreviousChapter: () -> Unit,
    onRequestNextChapter: () -> Unit,
    onVisibleProgress: (chapterIndex: Int, blockIndex: Int, blockCount: Int) -> Unit,
    ttsHighlightRange: IntRange?,
    markingHighlightRange: IntRange? = null,
    markingHighlightText: String? = null,
    activeChapterId: Long = 0L,
) {
    val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
    val fontFamily = rememberNovelReaderFontFamily(settings.font)
    val contentColor = Color(palette.textColor)
    val blocks = androidx.compose.runtime.remember(chapters) {
        buildNovelComposeWindowBlocks(chapters)
    }
    LaunchedEffect(markingHighlightRange, markingHighlightText, blocks) {
        val range = markingHighlightRange
        val cleanText = markingHighlightText?.trim().orEmpty()
        if (range == null && cleanText.isEmpty()) return@LaunchedEffect
        val targetBlockIndex = blocks.indexOfFirst { item ->
            if (item.chapter.chapterId != activeChapterId) return@indexOfFirst false
            val textBlock = item.block as? NovelComposeBlock.Text
            if (textBlock != null) {
                val textMatch = cleanText.isNotEmpty() && textBlock.original.contains(cleanText)
                val rangeMatch = range != null && textBlock.sourceRange?.let {
                    range.first in it || it.first in range
                } ?: false
                textMatch || rangeMatch
            } else false
        }
        if (targetBlockIndex >= 0) {
            listState.animateScrollToItem(targetBlockIndex)
        }
    }
    val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
    val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
    val paragraphSpacing = with(LocalDensity.current) {
        (settings.fontSizeSp * settings.lineSpacing * settings.paragraphSpacingLines).sp.toDp()
    }
    val statusBarInset = WindowInsets.statusBarsIgnoringVisibility
        .asPaddingValues()
        .calculateTopPadding()
    val gestureModifier = Modifier.novelReaderGestures(onTap, onLongPress, textSelectionActive)
    val reportWindowSelection: (NovelTextSelection?) -> Unit = { selection ->
        if (selection == null) {
            onTextSelectionChanged(null)
        } else {
            val matchedChapter = chapters.firstNotNullOfOrNull { chapter ->
                findNovelTextRange(chapter.content, selection.text)?.let { chapter }
            }
            onTextSelectionChanged(
                matchedChapter?.let {
                    selection.copy(
                        chapterId = it.chapterId,
                        chapterIndex = it.chapterIndex,
                        chapterText = it.content,
                    )
                } ?: selection,
            )
        }
    }
    NovelSelectionContainer(
        chapterId = chapters.firstOrNull()?.chapterId ?: 0L,
        chapterIndex = chapters.firstOrNull()?.chapterIndex ?: 0,
        chapterText = chapters.firstOrNull()?.content.orEmpty(),
        onSelectionChanged = reportWindowSelection,
    ) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(Color(palette.backgroundColor))
                .then(gestureModifier),
            contentPadding = PaddingValues(
                start = settings.marginHorizontal.dp,
                top = statusBarInset + settings.marginVertical.dp,
                end = settings.marginHorizontal.dp,
                bottom = settings.marginVertical.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(paragraphSpacing),
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
                        val original = formatNovelParagraphText(
                            text = block.original,
                            indentEnabled = settings.enableParagraphIndent,
                            spacingLines = settings.paragraphSpacingLines,
                        )
                        val translated = block.translation?.let {
                            formatNovelParagraphText(
                                text = it,
                                indentEnabled = settings.enableParagraphIndent,
                                spacingLines = settings.paragraphSpacingLines,
                            )
                        }
                        val style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = fontFamily,
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
                            textDirection = direction,
                            color = contentColor,
                        )
                        if (block.translation == null) {
                            if (block.inlineImages.isEmpty()) NovelMarkingText(
                                text = highlightedNovelText(
                                    text = original,
                                    sourceRange = block.sourceRange,
                                    highlightRange = ttsHighlightRange,
                                    highlightColor = MaterialTheme.colorScheme.secondaryContainer,
                                    markings = novelMarkings.filter { it.chapterId == item.chapter.chapterId },
                                    transientHighlightRange = if (item.chapter.chapterId == activeChapterId) markingHighlightRange else null,
                                    transientHighlightText = if (item.chapter.chapterId == activeChapterId) markingHighlightText else null,
                                ),
                                style = style,
                                textAlign = alignment,
                                sourceRange = block.sourceRange,
                                markings = novelMarkings.filter { it.chapterId == item.chapter.chapterId },
                                onMarkingClick = onMarkingClick,
                                textSelectionActive = textSelectionActive,
                            ) else NovelTextWithImageBlocks(
                                text = original,
                                inlineImages = block.inlineImages,
                                imageModel = imageModel,
                                imageContext = item.chapter.imageContext,
                                onImageClick = onImageClick,
                                style = style,
                                textAlign = alignment,
                            )
                        } else if (block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) {
                            Text(text = translated.orEmpty(), style = style, textAlign = alignment)
                        } else {
                            NovelTextWithImageBlocks(
                                text = original,
                                inlineImages = block.inlineImages,
                                imageModel = imageModel,
                                imageContext = item.chapter.imageContext,
                                onImageClick = onImageClick,
                                style = style.copy(fontSize = (settings.fontSizeSp * 0.86f).sp),
                                textAlign = alignment,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = translated.orEmpty(),
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
    animationsEnabled: Boolean = true,
    onSettingsChanged: (NovelReaderSettings) -> Unit = {},
    onToggleTranslation: () -> Unit = {},
    onToggleReplaceRules: () -> Unit = {},
    onShowReplaceRules: () -> Unit = {},
    onShowMarkings: () -> Unit = {},
    onDismissReplaceRules: () -> Unit = {},
    onReplaceRuleToggle: (org.skepsun.kototoro.core.replace.ReplaceRule, Boolean) -> Unit = { _, _ -> },
    onDismissMarkings: () -> Unit = {},
    onEditMarkingNote: (NovelMarkingEntity) -> Unit = {},
    onDeleteMarking: (NovelMarkingEntity) -> Unit = {},
    onDeleteBookmark: (org.skepsun.kototoro.bookmarks.domain.Bookmark) -> Unit = {},
    onOpenBookmark: (org.skepsun.kototoro.bookmarks.domain.Bookmark) -> Unit = {},
    onBookmark: () -> Unit = {},
    onTts: () -> Unit = {},
    onClearTranslationCache: () -> Unit = {},
    onChapterSelected: (Int) -> Unit = {},
    onSearchResultSelected: (NovelMarkingTarget) -> Unit = { target -> onChapterSelected(target.chapterIndex) },
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
    onPagedPositionChanged: (chapterId: Long, page: Int, pageCount: Int) -> Unit = { _, _, _ -> },
    onBookmarkPositionUnavailable: () -> Unit = {},
    renderContent: Boolean = true,
    onImageClick: ((String) -> Unit)? = null,
    onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onTextSelectionChanged: (NovelTextSelection?) -> Unit = {},
    onTextSelectionAction: (NovelTextSelection, NovelTextSelectionAction) -> Unit = { _, _ -> },
    onSelectionColorChange: (NovelTextSelection, Int) -> Unit = { _, _ -> },
    onSelectionStyleChange: (NovelTextSelection, Int) -> Unit = { _, _ -> },
    onMarkingClick: (NovelMarkingEntity, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onMarkingAction: (NovelMarkingEntity, NovelTextSelectionAction) -> Unit = { _, _ -> },
    onMarkingChangeStyle: (NovelMarkingEntity, Int, Int) -> Unit = { _, _, _ -> },
    onOpenMarkingDetail: (NovelMarkingEntity) -> Unit = {},
    onJumpToMarking: (NovelMarkingEntity) -> Unit = {},
    excerpt: NovelExcerptData? = null,
    onDismissExcerpt: () -> Unit = {},
    onSaveExcerpt: (NovelExcerptData, NovelExcerptConfiguration) -> Unit = { _, _ -> },
    onShareExcerpt: (NovelExcerptData, NovelExcerptConfiguration) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val selectionPanelPalette = settings?.let { novelReaderPalette(it.themePreset, isSystemInDarkTheme()) }
    if (renderContent && settings != null && state.content.isNotBlank()) {
        if (settings.readingMode == ReadingMode.PAGED) {
            ComposeNovelPagedChapter(
                state = state,
                chapters = state.continuousChapters,
                settings = settings,
                animationsEnabled = animationsEnabled,
                imageModel = imageModel,
                onImageClick = onImageClick,
                onTap = onTap,
                onLongPress = onLongPress,
                onTextSelectionChanged = onTextSelectionChanged,
                onMarkingClick = onMarkingClick,
                textSelectionActive = state.textSelection != null || state.selectedMarking != null,
                onBookmark = onBookmark,
                    onRequestPreviousChapter = onRequestPreviousChapter,
                    onRequestNextChapter = onRequestNextChapter,
                    onPageRequestConsumed = viewModel::consumePageRequest,
                    onBookmarkPositionsChanged = viewModel::publishBookmarkPositions,
                    onBookmarkRequestConsumed = { id, matched ->
                        if (viewModel.consumeBookmarkRequest(id) && !matched) onBookmarkPositionUnavailable()
                    },
                    onMarkingJumpResolved = viewModel::onMarkingJumpResolved,
                    onPositionChanged = { chapterId, chapterIndex, page, pageCount, charStart, charEnd, text ->
                    viewModel.focusContinuousChapter(chapterIndex)
                    onVisibleChapterChanged(chapterIndex)
                    viewModel.publishPagedPosition(page, pageCount, charStart, charEnd, text)
                    onPagedPositionChanged(chapterId, page, pageCount)
                },
                modifier = modifier,
            )
        } else {
            // 整窗块列表：既用于把归一化进度换算成窗口内的初始块索引，
            // 也用于给跨章节窗口的滚动位置做正确的边界裁剪。
            val windowBlocks = androidx.compose.runtime.remember(
                state.content,
                state.translation,
                state.continuousChapters,
            ) {
                val chapters = state.continuousChapters.ifEmpty {
                    listOf(
                        NovelComposeChapterContent(
                            chapterId = state.chapterId,
                            chapterIndex = state.chapterIndex,
                            chapterTitle = state.chapterTitle,
                            content = state.content,
                            translation = state.translation,
                        ),
                    )
                }
                buildNovelComposeWindowBlocks(chapters)
            }
            key("continuous") {
            // 切换阅读模式（分页→滚动）时没有可用的滚动位置，就按当前章节的
            // 归一化进度换算成窗口内块索引，避免进度被重置回章节开头。
            val initialScrollIndex = state.scrollPosition
                ?.firstVisibleBlock
                ?.coerceIn(0, windowBlocks.lastIndex.coerceAtLeast(0))
                ?: state.position
                    ?.takeIf { it.chapterId == state.chapterId && windowBlocks.isNotEmpty() }
                    ?.let { position ->
                        val chapterStart = windowBlocks.indexOfFirst {
                            it.chapter.chapterIndex == state.chapterIndex
                        }
                        if (chapterStart < 0) {
                            null
                        } else {
                            val chapterBlockCount = windowBlocks.count {
                                it.chapter.chapterIndex == state.chapterIndex
                            }
                            chapterStart +
                                (position.normalizedChapterProgress * chapterBlockCount.coerceAtLeast(1)).toInt()
                                    .coerceIn(0, (chapterBlockCount - 1).coerceAtLeast(0))
                        }
                    }
                ?: 0
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialScrollIndex,
                initialFirstVisibleItemScrollOffset = state.scrollPosition?.firstVisibleBlockOffsetPx ?: 0,
            )
            val bookmarkPositions = remember(windowBlocks, state.novelBookmarks, state.chapters) {
                resolveNovelBookmarkPositions(windowBlocks.map {
                    it.chapter.chapterId to ((it.block as? NovelComposeBlock.Text)?.original.orEmpty())
                }, state.novelBookmarks, state.chapters)
            }
            LaunchedEffect(bookmarkPositions) { viewModel.publishBookmarkPositions(bookmarkPositions) }
            LaunchedEffect(state.bookmarkRequest?.id, windowBlocks, state.loading) {
                val request = state.bookmarkRequest ?: return@LaunchedEffect
                if (state.loading || windowBlocks.none { it.chapter.chapterId == request.bookmark.chapterId }) {
                    return@LaunchedEffect
                }
                val target = bookmarkPositions[request.bookmark.pageId]
                if (target != null) listState.scrollToItem(target.windowIndex)
                if (viewModel.consumeBookmarkRequest(request.id)) {
                    windowBlocks.getOrNull(listState.firstVisibleItemIndex)?.let { visible ->
                        viewModel.focusContinuousChapter(visible.chapter.chapterIndex)
                        onVisibleChapterChanged(visible.chapter.chapterIndex)
                        onVisibleProgress(visible.chapter.chapterIndex, visible.chapterBlockIndex, visible.chapterBlockCount)
                    }
                    if (target == null) onBookmarkPositionUnavailable()
                }
            }
            LaunchedEffect(listState, state.chapterIndex, state.bookmarkRequest?.id) {
                if (state.bookmarkRequest != null) return@LaunchedEffect
                snapshotFlow {
                    if (listState.isScrollInProgress) {
                        null
                    } else {
                        NovelComposeScrollPosition(
                            firstVisibleBlock = listState.firstVisibleItemIndex,
                            firstVisibleBlockOffsetPx = listState.firstVisibleItemScrollOffset,
                        )
                    }
                }.filterNotNull().distinctUntilChanged().collect { position ->
                    viewModel.publishScrollPosition(position)
                    windowBlocks.getOrNull(position.firstVisibleBlock)?.let { visible ->
                        viewModel.publishBookmarkText(visible.chapter.chapterId,
                            (visible.block as? NovelComposeBlock.Text)?.original.orEmpty())
                    }
                }
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
                    onLongPress = onLongPress,
                    onTextSelectionChanged = onTextSelectionChanged,
                    onMarkingClick = onMarkingClick,
                    textSelectionActive = state.textSelection != null || state.selectedMarking != null,
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
                    novelMarkings = state.novelMarkings,
                    markingHighlightRange = state.markingHighlightRange,
                    markingHighlightText = state.markingHighlightText,
                    activeChapterId = state.chapterId,
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
                    onLongPress = onLongPress,
                    chapterId = state.chapterId,
                    chapterIndex = state.chapterIndex,
                    onTextSelectionChanged = onTextSelectionChanged,
                    onMarkingClick = onMarkingClick,
                    textSelectionActive = state.textSelection != null,
                    novelMarkings = state.novelMarkings,
                    listState = listState,
                    markingHighlightRange = state.markingHighlightRange,
                    markingHighlightText = state.markingHighlightText,
                    modifier = modifier,
                )
            }
        }
        }
    }
    state.textSelection?.let { selection ->
        NovelSelectionActionsOverlay(
            anchorRect = selection.anchorRect,
            fallbackAnchor = selection.selectionAnchor,
            onDismiss = {
                selection.clear()
                viewModel.publishTextSelection(null)
            },
        ) { isAbove, maxWidth, maxHeight ->
            NovelTextSelectionActions(
                selection = selection,
                onAction = onTextSelectionAction,
                selectedColor = state.activeMarkingColor,
                selectedStyle = state.activeMarkingStyle,
                onColorChange = { color -> onSelectionColorChange(selection, color) },
                onStyleChange = { style -> onSelectionStyleChange(selection, style) },
                isAbove = isAbove,
                panelColor = selectionPanelPalette?.let { Color(it.chromeBackgroundColor) },
                panelContentColor = selectionPanelPalette?.let { Color(it.chromeTextColor) },
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            )
        }
    }
    state.selectedMarking?.let { marking ->
        NovelSelectionActionsOverlay(
            anchorRect = state.selectedMarkingRect,
            fallbackAnchor = state.selectedMarkingAnchor,
            onDismiss = { viewModel.publishSelectedMarking(null) },
        ) { isAbove, maxWidth, maxHeight ->
            NovelTextSelectionActions(
                selection = NovelTextSelection(
                    text = marking.selectedText,
                    chapterId = marking.chapterId,
                    chapterIndex = marking.chapterIndex,
                    chapterText = "",
                    selectionAnchor = state.selectedMarkingAnchor,
                    anchorRect = state.selectedMarkingRect,
                    activeMarkingId = marking.id,
                    clear = { viewModel.publishSelectedMarking(null) },
                ),
                isMarking = true,
                selectedColor = marking.color,
                selectedStyle = marking.style,
                onColorChange = { color -> onMarkingChangeStyle(marking, color, marking.style) },
                onStyleChange = { style -> onMarkingChangeStyle(marking, marking.color, style) },
                note = marking.note,
                updatedAt = marking.updatedAt,
                onAction = { _, action -> onMarkingAction(marking, action) },
                onExpandNote = { onOpenMarkingDetail(marking) },
                isAbove = isAbove,
                panelColor = selectionPanelPalette?.let { Color(it.chromeBackgroundColor) },
                panelContentColor = selectionPanelPalette?.let { Color(it.chromeTextColor) },
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            )
        }
    }
    NovelReaderOverlay(
        loading = state.loading,
        message = state.message,
        controlsVisible = state.controlsVisible,
        animationsEnabled = animationsEnabled,
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
            replaceRulesEnabled = state.replaceRulesEnabled,
            onToggleReplaceRules = onToggleReplaceRules,
            onShowReplaceRules = { viewModel.showReplaceRules(); onShowReplaceRules() },
            onShowMarkings = { viewModel.showMarkings(); onShowMarkings() },
            onBookmark = onBookmark,
            onTts = onTts,
            onClearTranslationCache = onClearTranslationCache,
        )
    }
    if (!state.chromeEnabled && state.chaptersSheetVisible) {
        ComposeNovelChaptersSheet(
            chapters = state.chapters,
            currentIndex = state.currentChapterIndex,
            searchDocuments = state.continuousChapters,
            markings = state.novelMarkings,
            bookmarks = state.novelBookmarks,
            initialTab = state.chaptersSheetInitialTab,
            themePreset = settings?.themePreset ?: NovelReaderThemePreset.PAPER,
            onDismiss = {
                viewModel.dismissChapters()
                onModalDismissed()
            },
            onChapterSelected = {
                viewModel.dismissChapters()
                onModalDismissed()
                onChapterSelected(it)
            },
            onSearchResultSelected = { target ->
                viewModel.dismissChapters()
                onModalDismissed()
                onSearchResultSelected(target)
            },
            onJumpToMarking = {
                viewModel.dismissChapters()
                onModalDismissed()
                onJumpToMarking(it)
            },
            onOpenBookmark = {
                viewModel.dismissChapters()
                onModalDismissed()
                onOpenBookmark(it)
            },
            onEditMarkingNote = onEditMarkingNote,
            onDeleteMarking = onDeleteMarking,
            onDeleteBookmark = onDeleteBookmark,
        )
    }
    if (!state.chromeEnabled && state.replaceRulesSheetVisible) {
        ComposeNovelReplaceRulesSheet(
            rules = state.replaceRules,
            disabledRuleIds = state.disabledReplaceRuleIds,
            scopeName = state.workTitle,
            origin = state.replaceRulesOrigin,
            onDismiss = {
                viewModel.dismissReplaceRules()
                onDismissReplaceRules()
                onModalDismissed()
            },
            onToggle = onReplaceRuleToggle,
        )
    }
    if (!state.chromeEnabled && state.markingsSheetVisible) {
        ComposeNovelMarkingsSheet(
            bookmarks = state.novelBookmarks,
            markings = state.novelMarkings,
            chapters = state.chapters,
            bookTitle = state.workTitle,
            themePreset = settings?.themePreset ?: NovelReaderThemePreset.PAPER,
            onDismiss = {
                viewModel.dismissMarkings()
                onDismissMarkings()
                onModalDismissed()
            },
            onEditNote = onEditMarkingNote,
            onDelete = onDeleteMarking,
            onDeleteBookmark = onDeleteBookmark,
            onOpenBookmark = onOpenBookmark,
            onJumpToMarking = onJumpToMarking,
        )
    }
    excerpt?.let { data ->
        NovelExcerptSheet(
            data = data,
            onDismiss = onDismissExcerpt,
            onSave = { excerptData, config -> onSaveExcerpt(excerptData, config) },
            onShare = { excerptData, config -> onShareExcerpt(excerptData, config) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovelSelectionActionsOverlay(
    anchorRect: androidx.compose.ui.geometry.Rect?,
    fallbackAnchor: Offset? = null,
    onDismiss: () -> Unit,
    content: @Composable (isAbove: Boolean, maxWidth: Dp, maxHeight: Dp) -> Unit,
) {
    if (anchorRect == null && fallbackAnchor == null) return

    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBarsIgnoringVisibility
        .asPaddingValues()
        .calculateTopPadding()
    val topSafeInsetPx = with(density) { statusBarTop.roundToPx() }
    val bottomSafeInsetPx = with(density) {
        WindowInsets.navigationBarsIgnoringVisibility
            .asPaddingValues()
            .calculateBottomPadding()
            .roundToPx()
    }
    val spacingPx = with(density) { 6.dp.roundToPx() }
    val horizontalMarginPx = with(density) { 16.dp.roundToPx() }

    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onDismiss) {
                    detectNovelSelectionDismissTap(onDismiss)
                },
        )
        val placement = if (rootSize != IntSize.Zero && toolbarSize != IntSize.Zero) {
            calculateNovelSelectionToolbarPlacement(
                anchorRect = anchorRect,
                fallbackAnchor = fallbackAnchor,
                toolbarSize = toolbarSize,
                rootSize = rootSize,
                horizontalMargin = horizontalMarginPx,
                spacing = spacingPx,
                topSafeInset = topSafeInsetPx,
                bottomSafeInset = bottomSafeInsetPx,
            )
        } else {
            null
        }

        val maxToolbarWidth = if (rootSize != IntSize.Zero) {
            with(density) {
                (rootSize.width - horizontalMarginPx * 2)
                    .coerceAtLeast(1)
                    .toDp()
            }
        } else {
            440.dp
        }
        val maxToolbarHeight = if (rootSize != IntSize.Zero) {
            with(density) {
                (
                    rootSize.height - topSafeInsetPx - bottomSafeInsetPx - horizontalMarginPx * 2
                ).coerceAtLeast(1).toDp()
            }
        } else {
            600.dp
        }

        Box(
            modifier = Modifier
                .onSizeChanged { toolbarSize = it }
                .then(
                    if (placement != null) {
                        Modifier.offset { placement.offset }
                    } else {
                        Modifier.graphicsLayer { alpha = 0f }
                    },
                ),
        ) {
            content(placement?.isAbove ?: true, maxToolbarWidth, maxToolbarHeight)
        }
    }
}

internal data class NovelToolbarPlacement(
    val offset: IntOffset,
    val isAbove: Boolean,
)

internal fun calculateNovelSelectionToolbarPlacement(
    anchorRect: androidx.compose.ui.geometry.Rect?,
    fallbackAnchor: Offset?,
    toolbarSize: IntSize,
    rootSize: IntSize,
    horizontalMargin: Int = 16,
    spacing: Int = 6,
    topSafeInset: Int = 0,
    bottomSafeInset: Int = 0,
): NovelToolbarPlacement? {
    if (rootSize == IntSize.Zero || toolbarSize == IntSize.Zero) return null
    if (anchorRect == null && fallbackAnchor == null) return null

    val anchorCenterX = anchorRect?.let { it.left + it.width / 2f } ?: fallbackAnchor!!.x
    val anchorTop = anchorRect?.top ?: fallbackAnchor!!.y
    val anchorBottom = anchorRect?.bottom ?: fallbackAnchor!!.y

    val minX = horizontalMargin
    val maxX = (rootSize.width - toolbarSize.width - horizontalMargin).coerceAtLeast(minX)
    val x = (anchorCenterX - toolbarSize.width / 2f)
        .roundToInt()
        .coerceIn(minX, maxX)

    val aboveY = (anchorTop - toolbarSize.height - spacing).roundToInt()
    val belowY = (anchorBottom + spacing).roundToInt()
    val minY = (topSafeInset + horizontalMargin).coerceAtLeast(horizontalMargin)
    val maxY = (
        rootSize.height - toolbarSize.height - bottomSafeInset - horizontalMargin
    ).coerceAtLeast(minY)

    val isAbove = aboveY >= minY || belowY > maxY
    val y = if (isAbove) {
        aboveY.coerceIn(minY, maxY)
    } else {
        belowY.coerceIn(minY, maxY)
    }

    return NovelToolbarPlacement(IntOffset(x, y), isAbove)
}

internal fun calculateNovelSelectionToolbarOffset(
    anchorRect: androidx.compose.ui.geometry.Rect?,
    fallbackAnchor: Offset?,
    toolbarSize: IntSize,
    rootSize: IntSize,
    horizontalMargin: Int = 16,
    spacing: Int = 6,
    topSafeInset: Int = 0,
    bottomSafeInset: Int = 0,
): IntOffset? = calculateNovelSelectionToolbarPlacement(
    anchorRect = anchorRect,
    fallbackAnchor = fallbackAnchor,
    toolbarSize = toolbarSize,
    rootSize = rootSize,
    horizontalMargin = horizontalMargin,
    spacing = spacing,
    topSafeInset = topSafeInset,
    bottomSafeInset = bottomSafeInset,
)?.offset

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectNovelSelectionDismissTap(
    onDismiss: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        var moved = false
        var up: Offset? = null
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.pressed) {
                if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop ||
                    abs(change.position.y - down.position.y) > viewConfiguration.touchSlop
                ) {
                    moved = true
                }
            } else {
                up = change.position
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (!moved && up != null) {
            down.consume()
            onDismiss()
        }
    }
}

internal sealed interface NovelComposePage {
    val chapterId: Long
    val chapterIndex: Int
    val charStart: Int
    val charEnd: Int

    data class Text(
        val value: AnnotatedString,
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
    val style: androidx.compose.ui.text.TextStyle,
    val widthPx: Int,
    val heightPx: Int,
    val paragraphSpacingLines: Int,
    val paragraphIndentEnabled: Boolean,
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

internal fun findNovelPageForMarking(
    pages: List<NovelComposePage>,
    target: NovelMarkingTarget,
): Int {
    val candidatePages = pages.mapIndexedNotNull { index, page ->
        if (page.chapterId != target.chapterId) return@mapIndexedNotNull null
        if (page !is NovelComposePage.Text) return@mapIndexedNotNull null
        index to page
    }
    if (candidatePages.isEmpty()) return -1

    val cleanSelected = target.selectedText.trim()
    if (cleanSelected.isNotEmpty()) {
        // 1. Full text match
        val fullMatches = candidatePages.filter { (_, page) ->
            page.value.text.contains(cleanSelected)
        }
        if (fullMatches.isNotEmpty()) {
            return fullMatches.minByOrNull { (_, page) ->
                kotlin.math.abs(page.charStart - target.startOffset)
            }?.first ?: fullMatches.first().first
        }
        // 2. Prefix match (in case of page break across pages)
        if (cleanSelected.length > 8) {
            val prefix = cleanSelected.take(12.coerceAtMost(cleanSelected.length))
            val prefixMatches = candidatePages.filter { (_, page) ->
                page.value.text.contains(prefix)
            }
            if (prefixMatches.isNotEmpty()) {
                return prefixMatches.minByOrNull { (_, page) ->
                    kotlin.math.abs(page.charStart - target.startOffset)
                }?.first ?: prefixMatches.first().first
            }
        }
    }

    // 3. Offset range match
    val offsetMatch = candidatePages.firstOrNull { (_, page) ->
        target.startOffset in page.charStart..page.charEnd ||
            page.charStart in target.startOffset..target.endOffset
    }
    if (offsetMatch != null) {
        return offsetMatch.first
    }

    // 4. Closest page by charStart
    return candidatePages.minByOrNull { (_, page) ->
        kotlin.math.abs(page.charStart - target.startOffset)
    }?.first ?: candidatePages.first().first
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ComposeNovelPagedChapter(
    state: NovelComposeReaderUiState,
    chapters: List<NovelComposeChapterContent>,
    settings: NovelReaderSettings,
    animationsEnabled: Boolean,
    imageModel: (String) -> Any?,
    onImageClick: ((String) -> Unit)?,
    onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)?,
    onLongPress: (() -> Unit)?,
    onTextSelectionChanged: (NovelTextSelection?) -> Unit,
    onMarkingClick: (NovelMarkingEntity, androidx.compose.ui.geometry.Rect) -> Unit,
    textSelectionActive: Boolean,
    onBookmark: () -> Unit,
    onRequestPreviousChapter: () -> Unit,
    onRequestNextChapter: () -> Unit,
    onPageRequestConsumed: (Long) -> Unit,
    onBookmarkPositionsChanged: (Map<Long, NovelBookmarkResolvedPosition>) -> Unit,
    onBookmarkRequestConsumed: (Long, Boolean) -> Unit,
    onMarkingJumpResolved: () -> Unit = {},
    onPositionChanged: (Long, Int, Int, Int, Int, Int, String) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val fontFamily = rememberNovelReaderFontFamily(settings.font)
    // Pagination owns its measurer and only uses it from its background calculation.
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val palette = novelReaderPalette(settings.themePreset, isSystemInDarkTheme())
    val textColor = Color(palette.textColor)
    val direction = if (settings.textDirection == NovelTextDirection.RTL) TextDirection.Rtl else TextDirection.Ltr
    val alignment = if (direction == TextDirection.Rtl) TextAlign.Right else TextAlign.Start
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = fontFamily,
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
                animationSpec = KototoroMotion.PullSettleSpring,
            ) {
                pullOffsetPx = value
            }
        }
    }
    val pullToBookmarkModifier = Modifier.pointerInput(
        state.chapterId,
        bookmarkThresholdPx,
        textSelectionActive,
    ) {
        detectDownwardPullGestures(
            suppressPull = textSelectionActive,
            onPull = { dragAmount ->
                pullOffsetPx = (pullOffsetPx + dragAmount).coerceIn(0f, maximumPullPx)
            },
            onRelease = { settlePull(pullOffsetPx >= bookmarkThresholdPx) },
            onCancel = { settlePull(toggleBookmark = false) },
        )
    }
    val gestureModifier = Modifier.novelReaderGestures(onTap, onLongPress, textSelectionActive)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(palette.backgroundColor))
            .then(gestureModifier),
    ) {
        // 用户上边距从状态栏安全区下缘开始计算。即使全屏时状态栏隐藏也保留该安全区，
        // 避免挖孔遮挡，并确保唤出工具栏时正文高度与分页锚点完全不变。
        val statusBarInset = WindowInsets.statusBarsIgnoringVisibility
            .asPaddingValues()
            .calculateTopPadding()
        // 下边距同理从导航栏（手势条）安全区上缘开始：页脚状态栏与正文都不压在手势条上。
        val navigationBarInset = WindowInsets.navigationBarsIgnoringVisibility
            .asPaddingValues()
            .calculateBottomPadding()
        val contentWidthPx = with(density) {
            (maxWidth - settings.marginHorizontal.dp * 2).coerceAtLeast(1.dp).roundToPx()
        }
        val contentHeightPx = with(density) {
            (
                maxHeight - statusBarInset - navigationBarInset - settings.marginVertical.dp * 2 -
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
            style,
            contentWidthPx,
            contentHeightPx,
            settings.paragraphSpacingLines,
            settings.enableParagraphIndent,
        ) {
            NovelPaginationRequest(
                NovelPaginationLayoutKey(
                    style = style,
                    widthPx = contentWidthPx,
                    heightPx = contentHeightPx,
                    paragraphSpacingLines = settings.paragraphSpacingLines,
                    paragraphIndentEnabled = settings.enableParagraphIndent,
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
                    paragraphSpacingLines = settings.paragraphSpacingLines,
                    paragraphIndentEnabled = settings.enableParagraphIndent,
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
        // While a new pagination is being computed, keep showing the last completed result
        // that still contains the current chapter. Without this fallback the pager state
        // below would leave composition during the loading gap and reading progress
        // (settled page) would reset to the start of the window.
        val fallbackResult = paginationResult?.takeIf { result ->
            result.request !== paginationRequest &&
                result.pages.any {
                    it.chapterId == state.chapterId && it.chapterIndex == state.chapterIndex
                }
        }
        val displayedResult = exactResult ?: compatibleResult ?: fallbackResult
            val pages = displayedResult?.pages
            if (pages == null) {
                if (!state.loading) {
                    KototoroLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }
                return@BoxWithConstraints
        }
        if (pages.isEmpty()) return@BoxWithConstraints
        val bookmarkPositions = remember(pages, state.novelBookmarks, state.chapters) {
            resolveNovelBookmarkPositions(pages.map {
                it.chapterId to ((it as? NovelComposePage.Text)?.value?.text.orEmpty())
            }, state.novelBookmarks, state.chapters)
        }
        LaunchedEffect(bookmarkPositions) { onBookmarkPositionsChanged(bookmarkPositions) }
        // 首次创建分页器（冷启动或从滚动模式切回）时，用当前章节的归一化进度定位，
        // 避免进度被重置回章节第一页。rememberPagerState 只读取一次 initialPage，
        // 后续由 settledPageKey / pageRequest 机制接管。
        val initialPage = remember(displayedResult, state.chapterId, state.position, state.pendingMarkingTarget) {
            val pendingTarget = state.pendingMarkingTarget
            if (pendingTarget != null) {
                val targetPageIndex = findNovelPageForMarking(pages, pendingTarget)
                if (targetPageIndex >= 0) {
                    return@remember targetPageIndex
                }
            }
            val chapterStart = pages.indexOfFirst {
                it.chapterId == state.chapterId && it.chapterIndex == state.chapterIndex
            }
            if (chapterStart < 0) {
                0
            } else {
                val chapterPages = pages.count {
                    it.chapterId == state.chapterId && it.chapterIndex == state.chapterIndex
                }
                val progress = state.position
                    ?.takeIf { it.chapterId == state.chapterId }
                    ?.normalizedChapterProgress
                    ?: 0f
                chapterStart + (progress * chapterPages.coerceAtLeast(1)).toInt()
                    .coerceIn(0, (chapterPages - 1).coerceAtLeast(0))
            }
        }.coerceIn(pages.indices)
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = pages::size,
        )
        var settledPageKey by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        var readyGeneration by remember { mutableStateOf<NovelPaginationRequest?>(null) }
        val displayedGeneration = displayedResult.request
        LaunchedEffect(displayedGeneration) {
            if (state.bookmarkRequest != null) return@LaunchedEffect
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
        LaunchedEffect(state.bookmarkRequest?.id, exactResult, state.loading) {
            val request = state.bookmarkRequest ?: return@LaunchedEffect
            val result = exactResult ?: return@LaunchedEffect
            if (state.loading || result.pages.none { it.chapterId == request.bookmark.chapterId }) {
                return@LaunchedEffect
            }
            val target = bookmarkPositions[request.bookmark.pageId]
            if (target != null) pagerState.scrollToPage(target.windowIndex)
            readyGeneration = result.request
            onBookmarkRequestConsumed(request.id, target != null)
        }
        LaunchedEffect(state.pendingMarkingTarget, displayedResult) {
            val target = state.pendingMarkingTarget ?: return@LaunchedEffect
            val pagesList = displayedResult?.pages ?: return@LaunchedEffect
            if (pagesList.isEmpty()) return@LaunchedEffect
            if (pagesList.none { it.chapterId == target.chapterId }) return@LaunchedEffect
            val targetPageIndex = findNovelPageForMarking(pagesList, target)
            if (targetPageIndex >= 0) {
                if (targetPageIndex != pagerState.currentPage) {
                    pagerState.scrollToPage(targetPageIndex)
                }
                onMarkingJumpResolved()
            }
        }
        LaunchedEffect(state.markingHighlightRange, state.markingHighlightText, displayedResult) {
            val range = state.markingHighlightRange ?: return@LaunchedEffect
            val pagesList = displayedResult?.pages ?: return@LaunchedEffect
            val target = state.markingHighlightText?.let {
                NovelMarkingTarget(
                    chapterId = state.chapterId,
                    chapterIndex = state.chapterIndex,
                    startOffset = range.first,
                    endOffset = range.last + 1,
                    selectedText = it,
                )
            }
            val targetPageIndex = if (target != null) {
                findNovelPageForMarking(pagesList, target)
            } else {
                pagesList.indexOfFirst {
                    it.chapterId == state.chapterId && range.first in it.charStart..it.charEnd
                }
            }
            if (targetPageIndex >= 0 && targetPageIndex != pagerState.currentPage) {
                pagerState.scrollToPage(targetPageIndex)
            }
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
        LaunchedEffect(pagerState, displayedGeneration, readyGeneration, state.bookmarkRequest?.id) {
            if (state.bookmarkRequest != null) return@LaunchedEffect
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
                            page.chapterId,
                            page.chapterIndex,
                            localPage,
                            localPageCount,
                            page.charStart,
                            page.charEnd,
                            page.value.text,
                        )
                        is NovelComposePage.Image -> onPositionChanged(
                            page.chapterId,
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
        val effectiveAnimation = when {
            !animationsEnabled -> ReaderAnimation.NONE
            settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION -> ReaderAnimation.SIMULATION
            else -> ReaderAnimation.DEFAULT
        }
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
                .trackComposeReaderPageCurl(pageCurlState, effectiveAnimation == ReaderAnimation.SIMULATION)
                .graphicsLayer { translationY = pullOffsetPx },
        ) { index ->
            val page = pages[index]
            val isSimulation = effectiveAnimation == ReaderAnimation.SIMULATION
            val pageOffset = (index - pagerState.currentPage) - pagerState.currentPageOffsetFraction
            val curlOnEnd = !dualPage || novelDualPageCurlOnEnd(
                horizontalDragFraction = pageCurlState.horizontalDragFraction,
                isReversed = false,
            )
            val animationOffset = if (effectiveAnimation == ReaderAnimation.NONE) {
                pageOffset
            } else if (isSimulation && dualPage) {
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
            val pageTransform = animationOffset?.let { offset ->
                resolveComposeReaderPageTransform(
                    animation = effectiveAnimation,
                    pageOffset = offset,
                    isVertical = false,
                    isReversed = dualPage && !curlOnEnd,
                    isCurlUnfolding = isSimulationCurlUnfolding,
                )
            }
            val pageModifier = if (pageTransform != null) {
                Modifier
                    .zIndex(pageTransform.zIndex)
                    .graphicsLayer {
                        alpha = pageTransform.alpha
                        translationX = pageTransform.translationFactor * size.width
                        rotationY = pageTransform.rotationY
                        transformOrigin = pageTransform.transformOrigin
                        cameraDistance = READER_PAGE_CAMERA_DISTANCE
                    }
            } else {
                Modifier
            }
            Box(
                modifier = pageModifier
                    .fillMaxSize()
                    .then(
                        if (isSimulation && pageTransform != null) {
                            Modifier.composeReaderPageCurl(
                                transform = pageTransform,
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
                                bottom = navigationBarInset + settings.marginVertical.dp +
                                    if (settings.showReadingStatus) NovelReadingStatusReservedHeight else 0.dp,
                            ),
                    ) {
                        NovelSelectionContainer(
                            chapterId = page.chapterId,
                            chapterIndex = page.chapterIndex,
                            chapterText = chapters.firstOrNull {
                                it.chapterId == page.chapterId && it.chapterIndex == page.chapterIndex
                            }?.content.orEmpty(),
                            onSelectionChanged = onTextSelectionChanged,
                            renderedStart = page.charStart,
                            renderedText = page.value.text,
                        ) {
                            NovelMarkingText(
                                text = highlightedNovelText(
                                    text = page.value.text,
                                    sourceRange = page.charStart..page.charEnd,
                                    highlightRange = null,
                                    highlightColor = MaterialTheme.colorScheme.secondaryContainer,
                                    markings = state.novelMarkings.filter { it.chapterId == page.chapterId },
                                    transientHighlightRange = if (page.chapterId == state.chapterId) state.markingHighlightRange else null,
                                    transientHighlightText = if (page.chapterId == state.chapterId) state.markingHighlightText else null,
                                ),
                                style = style,
                                textAlign = alignment,
                                sourceRange = page.charStart..page.charEnd,
                                markings = state.novelMarkings.filter { it.chapterId == page.chapterId },
                                onMarkingClick = onMarkingClick,
                                textSelectionActive = textSelectionActive,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    is NovelComposePage.Image -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = settings.marginHorizontal.dp,
                                top = statusBarInset + settings.marginVertical.dp,
                                end = settings.marginHorizontal.dp,
                                bottom = navigationBarInset + settings.marginVertical.dp +
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
                        enter = fadeIn().whenReaderAnimationsEnabled(animationsEnabled),
                        exit = fadeOut().whenReaderAnimationsEnabled(animationsEnabled),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        NovelPageReadingStatus(
                            chapterTitle = chapterTitle,
                            progressLabel = "${localPage + 1} / $localPageCount",
                            settings = settings,
                            bottomInset = navigationBarInset,
                        )
                    }
                }
                if (isSimulation && pageTransform != null) {
                    ComposeReaderSimulationPageShadow(
                        transform = pageTransform,
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
    bottomInset: Dp,
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
            // The background still reaches the screen edge; only the text clears the gesture handle.
            modifier = Modifier.padding(
                start = settings.marginHorizontal.dp,
                top = 5.dp,
                end = settings.marginHorizontal.dp,
                bottom = 5.dp + bottomInset,
            ),
        ) {
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
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
    paragraphSpacingLines: Int,
    paragraphIndentEnabled: Boolean,
): List<NovelComposePage> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val pages = mutableListOf<NovelComposePage>()
    var textBatch = AnnotatedString.Builder()
    var textBatchSourceStart = 0

    suspend fun flushTextBatch() {
        if (textBatch.length == 0) return
        val text = textBatch.toAnnotatedString()
        textBatch = AnnotatedString.Builder()
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
            var trimmedEnd = end
            while (trimmedEnd > start && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
            val value = text.subSequence(start, trimmedEnd)
            if (value.text.isNotBlank()) {
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
                    block.translation == null -> formatNovelParagraphText(
                        block.original,
                        paragraphIndentEnabled,
                        paragraphSpacingLines,
                    )
                    block.displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY -> formatNovelParagraphText(
                        block.translation,
                        paragraphIndentEnabled,
                        paragraphSpacingLines,
                    )
                    else -> listOf(block.original, block.translation).joinToString(
                        separator = "\n".repeat(paragraphSpacingLines + 1),
                    ) { text ->
                        formatNovelParagraphText(text, paragraphIndentEnabled, paragraphSpacingLines)
                    }
                }
                if (displayed.isNotBlank()) {
                    if (textBatch.length > 0) {
                        textBatch.append("\n".repeat(paragraphSpacingLines + 1))
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
    paragraphSpacingLines: Int,
    paragraphIndentEnabled: Boolean,
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
                    paragraphSpacingLines = paragraphSpacingLines,
                    paragraphIndentEnabled = paragraphIndentEnabled,
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
    markings: List<NovelMarkingEntity> = emptyList(),
    transientHighlightRange: IntRange? = null,
    transientHighlightText: String? = null,
    transientHighlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFFFB74D).copy(alpha = 0.55f),
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        append(text)
        if (sourceRange != null) {
            if (highlightRange != null) {
                val start = maxOf(sourceRange.first, highlightRange.first)
                val end = minOf(sourceRange.last, highlightRange.last)
                if (start <= end) {
                    addStyle(
                        SpanStyle(background = highlightColor),
                        start = (start - sourceRange.first).coerceIn(0, text.length),
                        end = (end - sourceRange.first + 1).coerceIn(0, text.length),
                    )
                }
            }
            if (!transientHighlightText.isNullOrBlank()) {
                val clean = transientHighlightText.trim()
                val matchIdx = text.indexOf(clean)
                if (matchIdx >= 0) {
                    addStyle(
                        SpanStyle(background = transientHighlightColor),
                        start = matchIdx,
                        end = (matchIdx + clean.length).coerceAtMost(text.length),
                    )
                } else if (clean.length > 8) {
                    val prefix = clean.take(12.coerceAtMost(clean.length))
                    val prefixIdx = text.indexOf(prefix)
                    if (prefixIdx >= 0) {
                        addStyle(
                            SpanStyle(background = transientHighlightColor),
                            start = prefixIdx,
                            end = (prefixIdx + clean.length).coerceAtMost(text.length),
                        )
                    }
                }
            } else if (transientHighlightRange != null) {
                val start = maxOf(sourceRange.first, transientHighlightRange.first)
                val end = minOf(sourceRange.last, transientHighlightRange.last)
                if (start <= end) {
                    addStyle(
                        SpanStyle(background = transientHighlightColor),
                        start = (start - sourceRange.first).coerceIn(0, text.length),
                        end = (end - sourceRange.first + 1).coerceIn(0, text.length),
                    )
                }
            }
        }
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

private fun Modifier.novelReaderGestures(
    onTap: ((x: Float, y: Float, viewport: IntSize) -> Unit)?,
    onLongPress: (() -> Unit)?,
    textSelectionActive: Boolean,
): Modifier = if (onTap == null && onLongPress == null) {
    this
} else {
    pointerInput(onTap, onLongPress, textSelectionActive) {
        detectUnconsumedNovelReaderGestures(
            onTap = { offset -> onTap?.invoke(offset.x, offset.y, size) },
            onLongPress = { onLongPress?.invoke() },
            textSelectionActive = textSelectionActive,
        )
    }
}

private suspend fun PointerInputScope.detectUnconsumedNovelReaderGestures(
    onTap: (Offset) -> Unit,
    onLongPress: () -> Unit,
    textSelectionActive: Boolean,
) = kotlinx.coroutines.coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (down.isConsumed) return@awaitEachGesture

        if (textSelectionActive) {
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.none { it.pressed }) break
            } while (true)
            return@awaitEachGesture
        }

        var moved = false
        var cancelled = false
        var longPressDispatched = false
        var upPosition: Offset? = null
        val longPressJob = launch {
            delay(viewConfiguration.longPressTimeoutMillis)
            if (!moved && !cancelled) {
                longPressDispatched = true
                onLongPress()
            }
        }

        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                cancelled = true
                break
            }
            if (event.changes.count { it.pressed } > 1 || change.isConsumed) {
                cancelled = true
                longPressJob.cancel()
            }
            if (change.pressed) {
                val distanceX = abs(change.position.x - down.position.x)
                val distanceY = abs(change.position.y - down.position.y)
                if (distanceX > viewConfiguration.touchSlop || distanceY > viewConfiguration.touchSlop) {
                    moved = true
                    longPressJob.cancel()
                }
            } else {
                upPosition = change.position
            }
        } while (change.pressed)

        longPressJob.cancel()
        if (shouldDispatchNovelReaderTap(textSelectionActive, moved, cancelled, longPressDispatched)) {
            upPosition?.let(onTap)
        }
    }
}

internal fun shouldDispatchNovelReaderTap(
    textSelectionActive: Boolean,
    moved: Boolean,
    cancelled: Boolean,
    longPressDispatched: Boolean,
): Boolean = !textSelectionActive && !moved && !cancelled && !longPressDispatched

private suspend fun PointerInputScope.detectDownwardPullGestures(
    suppressPull: Boolean,
    onPull: (Float) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (suppressPull) {
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.none { it.pressed }) break
            } while (true)
            return@awaitEachGesture
        }
        var pullLocked = false
        var released = false
        var previousY = down.position.y
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
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
            if (change.isConsumed && !pullLocked) break
            if (pullLocked) {
                val dragAmount = change.position.y - previousY
                previousY = change.position.y
                change.consume()
                onPull(dragAmount)
                continue
            }

            val totalX = change.position.x - down.position.x
            val totalY = change.position.y - down.position.y
            if (!shouldStartNovelBookmarkPull(
                    textSelectionActive = suppressPull,
                    pointerConsumed = change.isConsumed,
                    totalX = totalX,
                    totalY = totalY,
                    touchSlop = viewConfiguration.touchSlop,
                )
            ) {
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

internal fun shouldStartNovelBookmarkPull(
    textSelectionActive: Boolean,
    pointerConsumed: Boolean,
    totalX: Float,
    totalY: Float,
    touchSlop: Float,
): Boolean = !textSelectionActive &&
    !pointerConsumed &&
    abs(totalX) <= touchSlop &&
    abs(totalY) > touchSlop

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

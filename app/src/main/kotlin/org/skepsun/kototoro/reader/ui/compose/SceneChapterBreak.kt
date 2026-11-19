package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * The scene readers draw pages from decoded images, and the chapter break page has no image source.
 *
 * The paged scene gets its break pages from the view model like the other paged readers. Each is drawn into a
 * bitmap by [SceneChapterBreakCaptures] and handed to the image pipeline as an ordinary page asset, so the
 * scene slides, covers and curls it exactly like an image page. The continuous scenes have no cover or curl
 * animation, so they add their break pages here as a strip between two chapters and draw them as an overlay
 * ([SceneChapterBreakOverlay]); that keeps the page list the view model shares with the legacy reader untouched.
 */

/** Share of the viewport a continuous strip covers along the scroll axis. */
internal const val SCENE_BREAK_STRIP_FRACTION = 0.34f

/** The card needs this much room whatever the viewport, so a short landscape screen does not clip it. */
private const val SCENE_BREAK_MIN_DP = 280f

/**
 * [pages] with a chapter break page at each chapter boundary and after the last page, for the continuous scenes whose
 * lists hold whole chapters. The trailing page shows even while the next chapter is still loading; it is the same
 * page (same key) as the boundary page once that chapter arrives. Lists that already carry break pages are returned
 * as they are.
 */
internal fun withSceneChapterBreaks(pages: List<ReaderPage>, enabled: Boolean): List<ReaderPage> {
    if (!enabled || pages.isEmpty() || pages.any { it.isChapterBreak }) return pages
    val result = ArrayList<ReaderPage>(pages.size + 1)
    pages.forEachIndexed { position, page ->
        result += page
        val next = pages.getOrNull(position + 1)
        if (next == null || next.chapterId != page.chapterId) result += ReaderPage.chapterBreak(page.chapterId, page.source)
    }
    return result
}

/**
 * The paged readers' list with break pages: [pages] is a reading-order window (the tail of the previous chapter, the
 * whole current chapter, the head of the next), so only edges that really are chapter edges get a page.
 *
 * - Between two chapters in the window.
 * - Before the first page when it is page 0 of a chapter that has a predecessor ([previousChapterIdOf]), even when
 *   that predecessor is not loaded.
 * - After the last page when it is the last page of its chapter ([pageCountOf] 0 or unknown counts as the last),
 *   whether or not a following chapter exists; the card says which.
 *
 * Every page is keyed by the chapter it follows, so the page in front of chapter C is the same page as the one after
 * C's predecessor and nothing flickers when the neighbour loads.
 */
internal fun insertChapterBreaks(
    pages: List<ReaderPage>,
    previousChapterIdOf: (chapterId: Long) -> Long?,
    pageCountOf: (chapterId: Long) -> Int,
): List<ReaderPage> {
    if (pages.isEmpty() || pages.any { it.isChapterBreak }) return pages
    val result = ArrayList<ReaderPage>(pages.size + 2)
    val first = pages.first()
    if (first.index == 0) {
        previousChapterIdOf(first.chapterId)?.let { result += ReaderPage.chapterBreak(it, first.source) }
    }
    pages.forEachIndexed { position, page ->
        result += page
        val next = pages.getOrNull(position + 1)
        val chapterEnds = if (next == null) {
            val count = pageCountOf(page.chapterId)
            count <= 0 || page.index >= count - 1
        } else {
            next.chapterId != page.chapterId
        }
        if (chapterEnds) result += ReaderPage.chapterBreak(page.chapterId, page.source)
    }
    return result
}

/**
 * The index in [shown] of the page at [position] in [source], for callers that hold positions of the unmodified
 * list. Break pages shift everything after them.
 */
internal fun sceneShownPosition(position: Int, source: List<ReaderPage>, shown: List<ReaderPage>): Int {
    if (source === shown) return position
    val key = source.getOrNull(position)?.readerKey ?: return position
    return shown.indexOfFirst { it.readerKey == key }.takeIf { it >= 0 } ?: position
}

/**
 * Size of a break page in px. A [fraction] below 1 makes a strip across the scroll axis ([horizontal] picks which),
 * never shorter than the card needs; 1 is a whole viewport.
 */
internal fun sceneChapterBreakSizePx(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    fraction: Float = 1f,
    horizontal: Boolean = false,
    density: Float = 1f,
): Pair<Float, Float> {
    if (fraction >= 1f) return viewportWidthPx to viewportHeightPx
    val minPx = SCENE_BREAK_MIN_DP * density
    return if (horizontal) {
        (viewportWidthPx * fraction).coerceAtLeast(minPx).coerceAtMost(viewportWidthPx) to viewportHeightPx
    } else {
        viewportWidthPx to (viewportHeightPx * fraction).coerceAtLeast(minPx).coerceAtMost(viewportHeightPx)
    }
}

internal fun sceneChapterBreakHint(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    fraction: Float = 1f,
    horizontal: Boolean = false,
    density: Float = 1f,
): PageGeometryHint? {
    if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return null
    val (width, height) = sceneChapterBreakSizePx(viewportWidthPx, viewportHeightPx, fraction, horizontal, density)
    return PageGeometryHint.Exact(width.toInt(), height.toInt())
}

/** The geometry hint of a strip between two chapters in a continuous scene. */
internal fun sceneStripBreakHint(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float,
    horizontal: Boolean,
): PageGeometryHint? =
    sceneChapterBreakHint(viewportWidthPx, viewportHeightPx, SCENE_BREAK_STRIP_FRACTION, horizontal, density)

/** The reader key to report for [key]: a break page reports as the last real page before it. */
internal fun sceneReportedKey(key: Long, pages: List<ReaderPage>): Long {
    val page = pages.firstOrNull { it.readerKey == key } ?: return key
    if (!page.isChapterBreak) return key
    val position = pages.indexOfFirst { it.readerKey == key }
    return pages.subList(0, position).lastOrNull { !it.isChapterBreak }?.readerKey
        ?: pages.firstOrNull { !it.isChapterBreak }?.readerKey
        ?: key
}

@Composable
internal fun SceneChapterBreakOverlay(
    page: ReaderPage,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    fraction: Float = 1f,
    horizontal: Boolean = false,
) {
    val density = LocalDensity.current
    val (width, height) = sceneChapterBreakSizePx(
        viewportWidthPx,
        viewportHeightPx,
        fraction,
        horizontal,
        density.density,
    )
    ReaderChapterBreakPage(page, Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() }))
}

/**
 * Draws every break page in [pages] into a bitmap the size of its scene page and registers it with [adapter].
 * The bitmap is drawn directly with the Android graphics API, so it exists before the first frame and does not depend
 * on anything being composed or laid out; it is drawn again whenever the theme, background, titles or size change.
 */
@Composable
internal fun SceneChapterBreakCaptures(
    pages: List<ReaderPage>,
    adapter: KototoroImagePipelineAdapter,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    backgroundColor: Int,
) {
    if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
    for (page in pages) {
        if (!page.isChapterBreak) continue
        key(page.readerKey) {
            SceneChapterBreakCapture(page, adapter, viewportWidthPx.toInt(), viewportHeightPx.toInt(), backgroundColor)
        }
    }
}

@Composable
private fun SceneChapterBreakCapture(
    page: ReaderPage,
    adapter: KototoroImagePipelineAdapter,
    widthPx: Int,
    heightPx: Int,
    backgroundColor: Int,
) {
    val density = LocalDensity.current.density
    val pageId = PageId(page.readerKey)
    val handler = LocalChapterBreakHandler.current
    val scheme = MaterialTheme.colorScheme
    val style = ChapterBreakBitmapStyle(
        background = backgroundColor,
        card = scheme.surfaceContainerHigh.toArgb(),
        title = scheme.onSurface.toArgb(),
        label = scheme.onSurfaceVariant.toArgb(),
        accent = scheme.primary.toArgb(),
    )
    val texts = ChapterBreakBitmapTexts(
        finishedLabel = stringResource(R.string.chapter_break_finished),
        finishedTitle = handler?.titleAt?.invoke(page.chapterId, 0),
        nextLabel = stringResource(R.string.chapter_break_next),
        nextTitle = handler?.titleAt?.invoke(page.chapterId, 1),
        lastText = stringResource(R.string.chapter_break_last),
    )
    LaunchedEffect(adapter, pageId, widthPx, heightPx, density, style, texts) {
        val bitmap = withContext(Dispatchers.Default) {
            renderChapterBreakBitmap(widthPx, heightPx, density, style, texts)
        }
        adapter.setSyntheticAsset(ReaderImageAsset.ComposeImage(pageId, bitmap.asImageBitmap()))
    }
    DisposableEffect(adapter, pageId) {
        onDispose { adapter.clearSyntheticAsset(pageId) }
    }
}

internal data class ChapterBreakBitmapStyle(
    val background: Int,
    val card: Int,
    val title: Int,
    val label: Int,
    val accent: Int,
)

internal data class ChapterBreakBitmapTexts(
    val finishedLabel: String,
    val finishedTitle: String?,
    val nextLabel: String,
    val nextTitle: String?,
    val lastText: String,
)

/**
 * The card of [org.skepsun.kototoro.core.ui.chapters.ChapterBreakCard] on an opaque [ChapterBreakBitmapStyle.background],
 * drawn with the same measures: a card at most 420dp wide, 24dp by 28dp padding, 28dp corners, and the same type
 * sizes. It is centred in a [widthPx] by [heightPx] bitmap.
 */
internal fun renderChapterBreakBitmap(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    style: ChapterBreakBitmapStyle,
    texts: ChapterBreakBitmapTexts,
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(style.background)

    fun dp(value: Float) = value * density
    val cardWidth = minOf(dp(420f), widthPx - dp(32f)).coerceAtLeast(dp(120f))
    val innerWidth = (cardWidth - dp(48f)).toInt().coerceAtLeast(1)
    val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun layout(text: String, sizeSp: Float, color: Int, typeface: Typeface?, maxLines: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = dp(sizeSp)
            typeface?.let { this.typeface = it }
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, innerWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    val blocks = ArrayList<Pair<StaticLayout, Float>>() // layout and the gap that follows it
    val finished = texts.finishedTitle?.takeIf { it.isNotBlank() }
    if (finished != null) {
        blocks += layout(texts.finishedLabel, 12f, style.label, medium, 1) to dp(4f)
        blocks += layout(finished, 16f, style.title, medium, 2) to 0f
    }
    val next = texts.nextTitle?.takeIf { it.isNotBlank() }
    if (next == null) {
        if (blocks.isNotEmpty()) blocks[blocks.lastIndex] = blocks.last().first to dp(8f)
        blocks += layout(texts.lastText, 14f, style.label, null, 2) to 0f
    } else {
        if (blocks.isNotEmpty()) blocks[blocks.lastIndex] = blocks.last().first to dp(16f)
        blocks += layout(texts.nextLabel, 12f, style.accent, medium, 1) to dp(4f)
        blocks += layout(next, 22f, style.title, null, 3) to 0f
    }

    val contentHeight = blocks.sumOf { (layout, gap) -> (layout.height + gap).toDouble() }.toFloat()
    val cardHeight = contentHeight + dp(56f)
    val left = (widthPx - cardWidth) / 2f
    val top = ((heightPx - cardHeight) / 2f).coerceAtLeast(0f)
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.card }
    canvas.drawRoundRect(RectF(left, top, left + cardWidth, top + cardHeight), dp(28f), dp(28f), cardPaint)

    var y = top + dp(28f)
    for ((layout, gap) in blocks) {
        canvas.save()
        canvas.translate(left + dp(24f), y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + gap
    }
    return bitmap
}

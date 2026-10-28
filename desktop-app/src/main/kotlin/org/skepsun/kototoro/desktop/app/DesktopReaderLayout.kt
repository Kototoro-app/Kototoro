package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourceChapterNavigation
import org.skepsun.kototoro.reader.core.*
import java.nio.file.Path

enum class DesktopReaderMode {
    SINGLE,
    DOUBLE,

    /** Android's webtoon mode: the pages scroll in one vertical strip. */
    CONTINUOUS,

    /** Android's continuous horizontal mode: the pages scroll in one horizontal strip, read left to right. */
    CONTINUOUS_HORIZONTAL,
    ;

    /** Whether the reader scrolls a strip instead of turning pages (both continuous modes do). */
    val isContinuous: Boolean get() = this == CONTINUOUS || this == CONTINUOUS_HORIZONTAL

    /** Whether the strip runs down the window (webtoon) rather than across it. */
    val isVerticalStrip: Boolean get() = this == CONTINUOUS
}

/** Android's `R.dimen.webtoon_pages_gap`, the gap "gaps in webtoon mode" puts between continuous pages. */
internal const val WEBTOON_PAGE_GAP_DP = 24

data class DesktopReaderSettings(
    val mode: DesktopReaderMode = DesktopReaderMode.SINGLE,
    val rightToLeft: Boolean = false,
    val fitMode: ZoomMode = ZoomMode.FIT_CENTER,
    /** Super-resolution of pages (RealCUGAN / Real-ESRGAN), as in Android's reader settings. */
    val upscale: org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting = org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting(),
    /** Android's page-turn animation (none / slide / cover / paper curl), same default. */
    val animation: org.skepsun.kototoro.core.prefs.ReaderAnimation = org.skepsun.kototoro.core.prefs.ReaderAnimation.DEFAULT,
    /** Android's colour correction (brightness, contrast, invert, grayscale, eye-care book tint). */
    val colorFilter: DesktopReaderColorFilter = DesktopReaderColorFilter(),
    val background: DesktopReaderBackground = DesktopReaderBackground.DEFAULT,
    /** Android's "show page numbers" overlay. */
    val pageNumbers: Boolean = false,
    /**
     * Android's `reader_chapter_title_bottom`: the work + chapter pill leaves the top bar and sits above the
     * progress dock instead, which is where a one-handed reader can reach it.
     */
    val chapterTitleAtBottom: Boolean = false,
    /**
     * Android's `reader_reduce_offscreen_quality` (the "reduce page preloading" switch): keep fewer pages loaded
     * outside the viewport, so adjacent pages may take longer to appear.
     */
    val reducePagePreloading: Boolean = false,
    /** Paged reading from top to bottom (Android's vertical mode); single and double pages only. */
    val vertical: Boolean = false,
    /** Android's "crop pages", kept separately for the paged and the continuous (webtoon) readers. */
    val cropPaged: Boolean = false,
    val cropContinuous: Boolean = false,
    /**
     * Android's `webtoon_gaps`: whether the continuous (webtoon) reader leaves
     * [WEBTOON_PAGE_GAP_DP] between pages instead of stacking them flush. Android's
     * `webtoon_pages_gap` dimension is 24dp and the switch is off by default.
     */
    val webtoonGaps: Boolean = false,
    /** Android's auto-scroll speed (0..1, shared timing in reader-core `ReaderAutoScroll`). */
    val autoScrollSpeed: Float = org.skepsun.kototoro.reader.core.ReaderAutoScroll.DEFAULT_SPEED,
    /** Android's reader actions: what a click (and a long press / right click) in each tap-grid area does. */
    /** Android's "double page cover page": the first page stays alone so spreads pair as printed. */
    val doublePageCover: Boolean = false,
    val tapGrid: Map<org.skepsun.kototoro.reader.domain.TapGridArea, org.skepsun.kototoro.reader.ui.tapgrid.TapActions> =
        org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig.defaults,
    /**
     * Android's `reader_continuous_horizontal_reversed`: the horizontal strip is read right to left, so the first page
     * sits at the right edge. Android keeps it apart from the mode for the same reason its scene engine does — the mode
     * says how pages are laid out, this says which way they are read.
     */
    val continuousHorizontalReversed: Boolean = false,
    /**
     * Android's `reader_mode_detect` ("autodetect reader mode", on by default): a content whose page at 30% of the
     * chapter is far taller than wide opens in the continuous strip instead of the chosen paged mode. The verdict,
     * like a manually picked mode, is remembered per content.
     */
    val readerModeDetection: Boolean = true,
) {
    /** Whether pages are cropped in the current reading mode. */
    val cropActive: Boolean get() = if (mode.isContinuous) cropContinuous else cropPaged
}

/** Android's `ReaderColorFilter`, applied with the shared [org.skepsun.kototoro.reader.domain.ReaderColorMatrix]. */
data class DesktopReaderColorFilter(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val inverted: Boolean = false,
    val grayscale: Boolean = false,
    val book: Boolean = false,
) {
    val isEmpty: Boolean get() =
        org.skepsun.kototoro.reader.domain.ReaderColorMatrix.isEmpty(brightness, contrast, inverted, grayscale, book)

    fun matrix(): FloatArray =
        org.skepsun.kototoro.reader.domain.ReaderColorMatrix.of(brightness, contrast, inverted, grayscale, book)
}

/** Android's reader backgrounds; AUTO and DEFAULT follow the app's light or dark appearance. */
enum class DesktopReaderBackground {
    DEFAULT, LIGHT, DARK, WHITE, BLACK, AUTO;

    /** Resolved per call, so an already-open options panel follows a language switch. */
    val title: String
        get() = when (this) {
            DEFAULT -> desktopText("reader.background.DEFAULT")
            LIGHT -> desktopText("reader.background.LIGHT")
            DARK -> desktopText("reader.background.DARK")
            WHITE -> desktopText("reader.background.WHITE")
            BLACK -> desktopText("reader.background.BLACK")
            AUTO -> desktopText("reader.background.AUTO")
        }
}

/** Paged reading direction: vertical wins over right-to-left, as Android's vertical mode. */
internal val DesktopReaderSettings.readingDirection: SceneReadingDirection get() = when {
    vertical -> SceneReadingDirection.TOP_TO_BOTTOM
    rightToLeft -> SceneReadingDirection.RIGHT_TO_LEFT
    else -> SceneReadingDirection.LEFT_TO_RIGHT
}

internal fun DesktopAppState.adjacentChapter(forward: Boolean) = chapter?.let {
    SourceChapterNavigation.adjacent(content?.chapters.orEmpty(), it.id, forward)
}

/** Only encoded artifacts and header geometry are retained; the renderer owns decoded visible pixels. */
/** [crop]: the content bounds in source pixels when "crop pages" removed plain margins (the scene uses its size). */
data class DesktopReaderImage(val path: Path, val width: Int, val height: Int, val regionSupported: Boolean = false,
    val crop: org.skepsun.kototoro.reader.core.IntRect? = null) {
    val displayWidth: Int get() = crop?.width ?: width
    val displayHeight: Int get() = crop?.height ?: height
}

/** The same shared scene determines visible images, page turns, placements and history anchoring. */
internal class DesktopReaderLayout(
    pages: List<SourcePage>,
    chapterId: Long,
    images: Map<Long, DesktopReaderImage>,
    settings: DesktopReaderSettings,
    pageIndex: Int,
    width: Int = 1000,
    height: Int = 700,
    geometry: Map<Long, IntSize> = emptyMap(),
) {
    val scene = PagedReaderScene(width.coerceAtLeast(1), height.coerceAtLeast(1),
        PagedSpreadConfig(isDoublePage = settings.mode == DesktopReaderMode.DOUBLE && !settings.vertical,
            isCoverOffset = settings.doublePageCover,
            readingDirection = settings.readingDirection,
            pageSpacingPx = 12, zoomMode = settings.fitMode),
        pages.mapIndexed { index, page ->
            val hint = images[page.id]?.let { PageGeometryHint.Exact(it.displayWidth, it.displayHeight) }
                ?: geometry[page.id]?.let { PageGeometryHint.Exact(it.width, it.height) }
                ?: PageGeometryHint.Estimated(.7f)
            PagedPageSpec(PageId(page.id), hint, chapterId, index)
        })
    val slotIndex = pages.getOrNull(pageIndex)?.let { scene.slotIndexOf(PageId(it.id)) } ?: -1
    val slot = scene.allSlots.getOrNull(slotIndex)
    val frame = slot?.let { scene.resolve(ReaderViewport(it.bounds)) }
    // Native-size/fit overflow may hide a page at rest; the complete canonical slot must still be loaded.
    val indices = slot?.placements.orEmpty().map { scene.indexOf(it.pageId) }.sorted()
    val anchorIndex = slot?.progressAnchorPageId?.let(scene::indexOf) ?: pageIndex

    fun turnIndex(forward: Boolean): Int? = scene.allSlots.getOrNull(slotIndex + if (forward) 1 else -1)
        ?.progressAnchorPageId?.let(scene::indexOf)
}

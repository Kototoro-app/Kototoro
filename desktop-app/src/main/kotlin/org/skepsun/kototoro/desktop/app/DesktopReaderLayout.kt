package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourceChapterNavigation
import org.skepsun.kototoro.reader.core.*
import java.nio.file.Path

enum class DesktopReaderMode { SINGLE, DOUBLE, CONTINUOUS }

data class DesktopReaderSettings(
    val mode: DesktopReaderMode = DesktopReaderMode.SINGLE,
    val rightToLeft: Boolean = false,
    val fitMode: ZoomMode = ZoomMode.FIT_CENTER,
    val automaticChapter: Boolean = false,
    /** Super-resolution of pages (RealCUGAN / Real-ESRGAN), as in Android's reader settings. */
    val upscale: org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting = org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting(),
    /** Android's page-turn animation (none / slide / cover / paper curl), same default. */
    val animation: org.skepsun.kototoro.core.prefs.ReaderAnimation = org.skepsun.kototoro.core.prefs.ReaderAnimation.DEFAULT,
    /** Android's colour correction (brightness, contrast, invert, grayscale, eye-care book tint). */
    val colorFilter: DesktopReaderColorFilter = DesktopReaderColorFilter(),
    val background: DesktopReaderBackground = DesktopReaderBackground.DEFAULT,
    /** Android's "show page numbers" overlay. */
    val pageNumbers: Boolean = false,
    /** Paged reading from top to bottom (Android's vertical mode); single and double pages only. */
    val vertical: Boolean = false,
    /** Android's "crop pages", kept separately for the paged and the continuous (webtoon) readers. */
    val cropPaged: Boolean = false,
    val cropContinuous: Boolean = false,
    /** Android's auto-scroll speed (0..1, shared timing in reader-core `ReaderAutoScroll`). */
    val autoScrollSpeed: Float = org.skepsun.kototoro.reader.core.ReaderAutoScroll.DEFAULT_SPEED,
    /** Android's reader actions: what a click (and a long press / right click) in each tap-grid area does. */
    /** Android's "double page cover page": the first page stays alone so spreads pair as printed. */
    val doublePageCover: Boolean = false,
    val tapGrid: Map<org.skepsun.kototoro.reader.domain.TapGridArea, org.skepsun.kototoro.reader.ui.tapgrid.TapActions> =
        org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig.defaults,
) {
    /** Whether pages are cropped in the current reading mode. */
    val cropActive: Boolean get() = if (mode == DesktopReaderMode.CONTINUOUS) cropContinuous else cropPaged
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
enum class DesktopReaderBackground(val title: String) {
    DEFAULT("默认"), LIGHT("浅色"), DARK("深色"), WHITE("白色"), BLACK("黑色"), AUTO("跟随系统"),
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

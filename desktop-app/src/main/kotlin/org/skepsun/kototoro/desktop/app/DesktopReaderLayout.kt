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
)

internal fun DesktopAppState.adjacentChapter(forward: Boolean) = chapter?.let {
    SourceChapterNavigation.adjacent(content?.chapters.orEmpty(), it.id, forward)
}

/** Only encoded artifacts and header geometry are retained; the renderer owns decoded visible pixels. */
data class DesktopReaderImage(val path: Path, val width: Int, val height: Int, val regionSupported: Boolean = false)

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
        PagedSpreadConfig(isDoublePage = settings.mode == DesktopReaderMode.DOUBLE,
            readingDirection = if (settings.rightToLeft) SceneReadingDirection.RIGHT_TO_LEFT
                else SceneReadingDirection.LEFT_TO_RIGHT,
            pageSpacingPx = 12, zoomMode = settings.fitMode),
        pages.mapIndexed { index, page ->
            val hint = images[page.id]?.let { PageGeometryHint.Exact(it.width, it.height) }
                ?: geometry[page.id]?.let { PageGeometryHint.Exact(it.width, it.height) }
                ?: PageGeometryHint.Estimated(.7f)
            PagedPageSpec(PageId(page.id), hint, chapterId, index)
        })
    private val slotIndex = pages.getOrNull(pageIndex)?.let { scene.slotIndexOf(PageId(it.id)) } ?: -1
    val slot = scene.allSlots.getOrNull(slotIndex)
    val frame = slot?.let { scene.resolve(ReaderViewport(it.bounds)) }
    // Native-size/fit overflow may hide a page at rest; the complete canonical slot must still be loaded.
    val indices = slot?.placements.orEmpty().map { scene.indexOf(it.pageId) }.sorted()
    val anchorIndex = slot?.progressAnchorPageId?.let(scene::indexOf) ?: pageIndex

    fun turnIndex(forward: Boolean): Int? = scene.allSlots.getOrNull(slotIndex + if (forward) 1 else -1)
        ?.progressAnchorPageId?.let(scene::indexOf)
}

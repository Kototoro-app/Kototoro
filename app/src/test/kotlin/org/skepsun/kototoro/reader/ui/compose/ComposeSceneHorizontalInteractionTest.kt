package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.HorizontalReaderScene
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.SceneAxisProjection
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.render.compose.ComposeScenePrimaryScrollState

class ComposeSceneHorizontalInteractionTest {

    @Test
    fun `resolveViewportOriginForPage accurately positions LTR and RTL pages`() {
        val pages = listOf(
            PageId(1L) to PageGeometryHint.Exact(800, 1200),
            PageId(2L) to PageGeometryHint.Exact(1200, 1200),
            PageId(3L) to PageGeometryHint.Exact(1000, 1200),
        )

        // LTR Scene: Page 1 [0..800], Page 2 [800..2000], Page 3 [2000..3000]. Total = 3000.
        val sceneLtr = HorizontalReaderScene(
            availableHeight = 1200,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
            pageSpacingPx = 0,
        )

        val vpWidth = 1000f
        assertEquals(0f, sceneLtr.resolveViewportOriginForPage(PageId(1L), vpWidth, 0f))
        assertEquals(800f, sceneLtr.resolveViewportOriginForPage(PageId(2L), vpWidth, 0f))
        assertEquals(950f, sceneLtr.resolveViewportOriginForPage(PageId(2L), vpWidth, 150f))
        assertEquals(2000f, sceneLtr.resolveViewportOriginForPage(PageId(3L), vpWidth, 0f))
        assertNull(sceneLtr.resolveViewportOriginForPage(PageId(999L), vpWidth, 0f))

        // RTL Scene: Total = 3000.
        // Page 1 is first in reading order: placed at [2200..3000]
        // Page 2 is second in reading order: placed at [1000..2200]
        // Page 3 is third in reading order: placed at [0..1000]
        val sceneRtl = HorizontalReaderScene(
            availableHeight = 1200,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
            pageSpacingPx = 0,
        )

        // For Page 1 in RTL: bounds.right = 3000. Viewport left = 3000 - 1000 = 2000f.
        assertEquals(2000f, sceneRtl.resolveViewportOriginForPage(PageId(1L), vpWidth, 0f))
        // With 150px intra-page offset: viewport left = 3000 - 1000 - 150 = 1850f.
        assertEquals(1850f, sceneRtl.resolveViewportOriginForPage(PageId(1L), vpWidth, 150f))

        // For Page 2 in RTL: bounds.right = 2200. Viewport left = 2200 - 1000 = 1200f.
        assertEquals(1200f, sceneRtl.resolveViewportOriginForPage(PageId(2L), vpWidth, 0f))

        // For Page 3 in RTL: bounds.right = 1000. Viewport left = 1000 - 1000 = 0f.
        assertEquals(0f, sceneRtl.resolveViewportOriginForPage(PageId(3L), vpWidth, 0f))
    }

    @Test
    fun `resolveViewportOriginForPage roundtrips with scene resolve progress`() {
        val pages = listOf(
            PageId(10L) to PageGeometryHint.Exact(900, 1200),
            PageId(20L) to PageGeometryHint.Exact(1100, 1200),
            PageId(30L) to PageGeometryHint.Exact(1000, 1200),
        )

        val vpWidth = 1000f
        val vpHeight = 1200f

        // 1. Roundtrip for LTR
        val sceneLtr = HorizontalReaderScene(
            availableHeight = 1200,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )
        val ltrOrigin = sceneLtr.resolveViewportOriginForPage(PageId(20L), vpWidth, intraPageOffset = 250f)
        assertNotNull(ltrOrigin)
        val ltrFrame = sceneLtr.resolve(ReaderViewport(FloatRect.fromLtwh(ltrOrigin!!, 0f, vpWidth, vpHeight)))
        assertEquals(PageId(20L), ltrFrame.progress.activePageId)
        assertEquals(250f, ltrFrame.progress.intraPageOffsetPx)

        // 2. Roundtrip for RTL
        val sceneRtl = HorizontalReaderScene(
            availableHeight = 1200,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )
        val rtlOrigin = sceneRtl.resolveViewportOriginForPage(PageId(20L), vpWidth, intraPageOffset = 320f)
        assertNotNull(rtlOrigin)
        val rtlFrame = sceneRtl.resolve(ReaderViewport(FloatRect.fromLtwh(rtlOrigin!!, 0f, vpWidth, vpHeight)))
        assertEquals(PageId(20L), rtlFrame.progress.activePageId)
        assertEquals(320f, rtlFrame.progress.intraPageOffsetPx)
    }

    @Test
    fun `ComposeScenePrimaryScrollState manages clamping and zero-CLS snapBy`() {
        val scrollState = ComposeScenePrimaryScrollState(initialOffset = 500f, maxOffset = 2000f)
        assertEquals(500f, scrollState.offset)

        // Snap by delta (positive)
        scrollState.snapBy(300f)
        assertEquals(800f, scrollState.offset)

        // Snap by delta (negative)
        scrollState.snapBy(-200f)
        assertEquals(600f, scrollState.offset)

        // Over-clamp positive
        scrollState.snapBy(5000f)
        assertEquals(2000f, scrollState.offset)

        // Over-clamp negative
        scrollState.snapBy(-10000f)
        assertEquals(0f, scrollState.offset)
    }

    @Test
    fun `anchored compensation keeps viewport stable when page geometry updates`() {
        // 3 estimated pages of ratio 1.0 (width = 1200 each)
        val sceneLtr = HorizontalReaderScene(
            availableHeight = 1200,
            defaultViewportWidth = 1200,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1.0f), // [0..1200]
                PageId(2L) to PageGeometryHint.Estimated(1.0f), // [1200..2400]
                PageId(3L) to PageGeometryHint.Estimated(1.0f), // [2400..3600]
            ),
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        // Viewport currently viewing Page 2 at offset 1500 (300px into Page 2)
        val currentVp = ReaderViewport(FloatRect.fromLtwh(1500f, 0f, 1000f, 1200f))

        // Page 1 resolves exact dimensions: actual width is 800 instead of 1200 (shrink by 400px)
        val comp = sceneLtr.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(800, 1200),
            currentViewport = currentVp,
        )

        assertNotNull(comp)
        // Since Page 1 shrank by 400px before Page 2, Page 2 shifted left by 400px (from 1200 to 800).
        // DeltaX should be -400px so scrollState snaps by -400px!
        assertEquals(-400f, comp!!.deltaX)

        val newOffset = 1500f + comp.deltaX // 1100f
        val newVp = ReaderViewport(FloatRect.fromLtwh(newOffset, 0f, 1000f, 1200f))
        val newFrame = sceneLtr.resolve(newVp)
        // Visual content inside viewport remains Page 2 at exactly 300px relative offset!
        assertEquals(PageId(2L), newFrame.progress.activePageId)
        assertEquals(300f, newFrame.progress.intraPageOffsetPx)
    }

    @Test
    fun `cross-chapter page prepend provides exact deltaX anchor compensation`() {
        // Initial chapter 2: Pages 3, 4
        val sceneLtr = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(3L) to PageGeometryHint.Exact(1000, 1000), // [0..1000]
                PageId(4L) to PageGeometryHint.Exact(1000, 1000), // [1000..2000]
            ),
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        val vp = ReaderViewport(FloatRect.fromLtwh(300f, 0f, 1000f, 1000f))
        val frameBefore = sceneLtr.resolve(vp)
        assertEquals(PageId(3L), frameBefore.progress.activePageId)
        assertEquals(300f, frameBefore.progress.intraPageOffsetPx)

        // Prepend chapter 1: Pages 1, 2 (each 1000px wide)
        val expandedPages = listOf(
            PageId(1L) to PageGeometryHint.Exact(1000, 1000),
            PageId(2L) to PageGeometryHint.Exact(1000, 1000),
            PageId(3L) to PageGeometryHint.Exact(1000, 1000),
            PageId(4L) to PageGeometryHint.Exact(1000, 1000),
        )

        val comp = sceneLtr.updatePages(expandedPages, vp)
        assertNotNull(comp)
        // 2000px of pages prepended before Page 3
        assertEquals(2000f, comp!!.deltaX)

        // Applying deltaX keeps visual position anchored on Page 3 at 300px intra-page offset
        val compensatedVp = ReaderViewport(FloatRect.fromLtwh(vp.bounds.left + comp.deltaX, 0f, 1000f, 1000f))
        val frameAfter = sceneLtr.resolve(compensatedVp)
        assertEquals(PageId(3L), frameAfter.progress.activePageId)
        assertEquals(300f, frameAfter.progress.intraPageOffsetPx)
    }

    @Test
    fun `updatePages adopts chapter ratio for unknown pages while keeping exact geometries`() {
        val scene = HorizontalReaderScene(
            availableHeight = 2000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(700, 1000), // fit-height width 1400 -> [0..1400]
                PageId(2L) to PageGeometryHint.Estimated(ratio = 1f), // width = 1000 -> [1400..2400]
            ),
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
            pageSpacingPx = 0,
        )

        // Chapter-average ratio converged from the decoded pages: still-unknown pages adopt it
        // so their loading placeholder matches the real image size (fit-height width = 2000 * 0.7).
        val relayoutHints = listOf(
            PageId(1L) to PageGeometryHint.Exact(700, 1000),
            PageId(2L) to PageGeometryHint.AspectRatio(0.7f),
        )
        val vp = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 2000f))
        val comp = scene.updatePages(relayoutHints, vp)

        // The exact geometry of page 1 is preserved and the viewport stays anchored on it.
        assertNotNull(comp)
        assertEquals(0f, comp!!.deltaX)
        assertEquals(2800f, scene.totalSceneWidth)
        val frame = scene.resolve(ReaderViewport(FloatRect.fromLtwh(2000f, 0f, 1000f, 2000f)))
        val page2 = frame.visibleNodes.first { it.pageId == PageId(2L) }
        assertEquals(1400f, page2.sceneBounds.left)
        assertEquals(2800f, page2.sceneBounds.right)
    }

    @Test
    fun `SceneAxisProjection maps physical drag velocities to forward reading velocity`() {
        // In LTR: dragging finger left (deltaX > 0) -> velocityX is positive -> forward velocity is positive
        val motionLtrForward = ViewportMotion(velocityX = 1500f, velocityY = 0f)
        val ltrForwardVelocity = SceneAxisProjection.forwardVelocity(motionLtrForward, SceneReadingDirection.LEFT_TO_RIGHT)
        assertEquals(1500f, ltrForwardVelocity)

        // In RTL: dragging finger right (deltaX < 0) -> velocityX is negative (-1500)
        // Because reading progresses towards smaller scene X coordinates, forward velocity is positive!
        val motionRtlForward = ViewportMotion(velocityX = -1500f, velocityY = 0f)
        val rtlForwardVelocity = SceneAxisProjection.forwardVelocity(motionRtlForward, SceneReadingDirection.RIGHT_TO_LEFT)
        assertEquals(1500f, rtlForwardVelocity)

        // Dragging backwards in RTL (finger left -> velocityX positive 1500) -> forward velocity is negative
        val motionRtlBackward = ViewportMotion(velocityX = 1500f, velocityY = 0f)
        val rtlBackwardVelocity = SceneAxisProjection.forwardVelocity(motionRtlBackward, SceneReadingDirection.RIGHT_TO_LEFT)
        assertEquals(-1500f, rtlBackwardVelocity)
    }

    @Test
    fun `SceneAxisProjection expands viewport ahead and behind based on reading direction`() {
        val baseVp = ReaderViewport(FloatRect.fromLtwh(1000f, 0f, 1000f, 1200f))

        // LTR expansion: ahead = +2000 (right), behind = -500 (left)
        val ltrExpanded = SceneAxisProjection.expand(
            baseVp,
            SceneReadingDirection.LEFT_TO_RIGHT,
            aheadPx = 2000f,
            behindPx = 500f,
        )
        assertEquals(500f, ltrExpanded.bounds.left)
        assertEquals(3500f, ltrExpanded.bounds.width) // 1000 + 500 + 2000
        assertEquals(4000f, ltrExpanded.bounds.right)

        // RTL expansion: ahead = +2000 (left!), behind = -500 (right!)
        val rtlExpanded = SceneAxisProjection.expand(
            baseVp,
            SceneReadingDirection.RIGHT_TO_LEFT,
            aheadPx = 2000f,
            behindPx = 500f,
        )
        // In RTL: left expands forward by aheadPx (1000 - 2000 coerced to 0)
        assertEquals(0f, rtlExpanded.bounds.left)
        assertEquals(3500f, rtlExpanded.bounds.width)
    }

    @Test
    fun `device rotation and viewport resize preserves active page anchor`() {
        val pages = listOf(
            PageId(1L) to PageGeometryHint.Exact(800, 1000),
            PageId(2L) to PageGeometryHint.Exact(1600, 1000),
        )

        val sceneRtl = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        // In portrait (width = 1000):
        // Page 1 is [1600..2400]. Viewport origin to view Page 1 = 2400 - 1000 = 1400.
        val portraitOrigin = sceneRtl.resolveViewportOriginForPage(PageId(1L), viewportExtent = 1000f)
        assertEquals(1400f, portraitOrigin)

        // In landscape (width = 2000):
        // Viewport origin to view Page 1 = 2400 - 2000 = 400.
        val landscapeOrigin = sceneRtl.resolveViewportOriginForPage(PageId(1L), viewportExtent = 2000f)
        assertEquals(400f, landscapeOrigin)

        // In both cases, the right edge of Page 1 is flush with the right edge of the viewport:
        // Portrait: left (1400) + width (1000) = 2400.
        // Landscape: left (400) + width (2000) = 2400.
        assertEquals(2400f, portraitOrigin!! + 1000f)
        assertEquals(2400f, landscapeOrigin!! + 2000f)
    }
}

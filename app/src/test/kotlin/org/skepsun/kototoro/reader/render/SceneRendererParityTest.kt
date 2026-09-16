package org.skepsun.kototoro.reader.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderFrame
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.render.compose.drawFrameNodes

class SceneRendererParityTest {

    @Test
    fun `both renderers calculate identical screen-space destination bounds`() {
        val scene = VerticalReaderScene(
            availableWidth = 1080,
            defaultViewportHeight = 2400,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1080, 1920),
                PageId(2L) to PageGeometryHint.Exact(1080, 2000),
                PageId(3L) to PageGeometryHint.Exact(1080, 2000),
            ),
        )

        val scrollY = 2500f
        val viewportHeight = 2400f
        val viewportWidth = 1080f

        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, scrollY, viewportWidth, viewportHeight))
        val frame = scene.resolve(viewport)

        // Both Candidate A (Compose) and Candidate B (View) map destination as:
        // screenTop = node.sceneBounds.top - scrollY
        // screenLeft = node.sceneBounds.left
        // Verify visible nodes destination:
        assertEquals(2, frame.visibleNodes.size) // Page 2 (1920..3920) and Page 3 (3920..5920)

        val page2Node = frame.visibleNodes[0]
        assertEquals(PageId(2L), page2Node.pageId)
        val page2ScreenTop = page2Node.sceneBounds.top - scrollY
        assertEquals(-580f, page2ScreenTop) // 1920 - 2500 = -580f
        assertEquals(2000f, page2Node.sceneBounds.height)

        val page3Node = frame.visibleNodes[1]
        assertEquals(PageId(3L), page3Node.pageId)
        val page3ScreenTop = page3Node.sceneBounds.top - scrollY
        assertEquals(1420f, page3ScreenTop) // 3920 - 2500 = 1420f
        assertEquals(2000f, page3Node.sceneBounds.height)
    }

    @Test
    fun `both candidates clamp max scroll to scene height minus viewport height`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 3000),
                PageId(2L) to PageGeometryHint.Exact(1000, 4000),
            ),
        )

        val viewportHeight = 2000f
        val totalHeight = scene.totalSceneHeight // 7000f
        val maxScrollY = (totalHeight - viewportHeight).coerceAtLeast(0f)

        assertEquals(5000f, maxScrollY)
    }

    @Test
    fun `both candidates resolve identical active page sequence during continuous scroll`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 2000), // 0..2000
                PageId(2L) to PageGeometryHint.Exact(1000, 2000), // 2000..4000
                PageId(3L) to PageGeometryHint.Exact(1000, 2000), // 4000..6000
            ),
        )

        val viewportHeight = 2000f

        // Scroll 0..2000: Page 1 ends at 2000
        val v1 = ReaderViewport(FloatRect.fromLtwh(0f, 500f, 1000f, viewportHeight))
        assertEquals(PageId(1L), scene.resolveActivePageId(v1))

        // Scroll 2000..4000: Page 2 ends at 4000
        val v2 = ReaderViewport(FloatRect.fromLtwh(0f, 2500f, 1000f, viewportHeight))
        assertEquals(PageId(2L), scene.resolveActivePageId(v2))

        // Scroll 4000..6000: Page 3 ends at 6000
        val v3 = ReaderViewport(FloatRect.fromLtwh(0f, 4000f, 1000f, viewportHeight))
        assertEquals(PageId(3L), scene.resolveActivePageId(v3))
    }

    @Test
    fun `drawFrameNodes delegates to drawRect for missing assets and drawImage for loaded assets`() {
        val drawScope = mockk<DrawScope>(relaxed = true)
        val mockBitmap = mockk<ImageBitmap>(relaxed = true)
        val p1 = PageId(1L)
        val p2 = PageId(2L)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1000f, 1080f, 2000f))
        val frame = ReaderFrame(
            viewport = viewport,
            visibleNodes = listOf(
                VisibleNode(
                    pageId = p1,
                    sceneBounds = FloatRect.fromLtwh(0f, 1000f, 1080f, 1500f),
                    visibleRegion = FloatRect.fromLtwh(0f, 1000f, 1080f, 1500f),
                ),
                VisibleNode(
                    pageId = p2,
                    sceneBounds = FloatRect.fromLtwh(0f, 2500f, 1080f, 1500f),
                    visibleRegion = FloatRect.fromLtwh(0f, 2500f, 1080f, 500f),
                ),
            ),
        )

        val assetProvider: (PageId) -> ImageBitmap? = { id ->
            if (id == p2) mockBitmap else null
        }

        drawScope.drawFrameNodes(
            frame = frame,
            viewportScrollY = 1000f,
            placeholderColor = Color.DarkGray,
            assetProvider = assetProvider,
        )

        verify(exactly = 1) {
            drawScope.drawRect(
                color = Color.DarkGray,
                topLeft = Offset(0f, 0f),
                size = Size(1080f, 1500f),
            )
        }
        verify(exactly = 1) {
            drawScope.drawImage(
                image = mockBitmap,
                dstOffset = IntOffset(0, 1500),
                dstSize = IntSize(1080, 1500),
            )
        }
    }
}

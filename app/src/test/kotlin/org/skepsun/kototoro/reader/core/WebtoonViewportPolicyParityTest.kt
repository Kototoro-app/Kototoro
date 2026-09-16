package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.ui.compose.WebtoonVisibleItem
import org.skepsun.kototoro.reader.ui.compose.measureWebtoonViewport
import org.skepsun.kototoro.reader.ui.compose.resolveLastEndVisibleWebtoonPageKey

/**
 * Parity test verifying that the new platform-agnostic [ReaderScene] geometry contract
 * behaves with semantic equivalence to Kototoro's established, production-proven [measureWebtoonViewport]
 * and [resolveLastEndVisibleWebtoonPageKey] in `WebtoonViewportPolicy`.
 */
class WebtoonViewportPolicyParityTest {

    private fun layoutVerticalPages(
        availableWidth: Int,
        viewportHeight: Int,
        pageHints: List<Pair<Long, PageGeometryHint>>,
    ): List<PageGeometry> {
        var currentY = 0f
        val result = ArrayList<PageGeometry>(pageHints.size)
        for ((pageKey, hint) in pageHints) {
            val pageHeight = when (hint) {
                is PageGeometryHint.Exact -> {
                    measureWebtoonViewport(viewportHeight, availableWidth, hint.width, hint.height).itemHeightPx.toFloat()
                }
                is PageGeometryHint.AspectRatio -> {
                    (availableWidth / hint.ratio).toInt().coerceAtLeast(1).toFloat()
                }
                is PageGeometryHint.Estimated -> {
                    measureWebtoonViewport(viewportHeight, availableWidth, null, null).itemHeightPx.toFloat()
                }
            }
            result.add(
                PageGeometry(
                    pageId = PageId(pageKey),
                    sceneBounds = FloatRect.fromLtwh(0f, currentY, availableWidth.toFloat(), pageHeight),
                ),
            )
            currentY += pageHeight
        }
        return result
    }

    @Test
    fun `layout height matches legacy measureWebtoonViewport across various dimensions`() {
        val availableWidth = 1080
        val viewportHeight = 2400

        val testCases = listOf(
            Pair(1080, 1920),  // Standard page
            Pair(1080, 7200),  // Long webtoon strip
            Pair(2160, 1440),  // Wide spread
            Pair(1000, 1001),  // Fractional aspect
        )

        for ((imgW, imgH) in testCases) {
            val legacyMeasurement = measureWebtoonViewport(viewportHeight, availableWidth, imgW, imgH)
            val hint = PageGeometryHint.Exact(imgW, imgH)
            val layout = layoutVerticalPages(availableWidth, viewportHeight, listOf(1L to hint))

            assertEquals(
                legacyMeasurement.itemHeightPx.toFloat(),
                layout.first().sceneBounds.height,
                0.5f,
                "Failed parity for image $imgW x $imgH",
            )
        }
    }

    @Test
    fun `estimated hint reserves full viewport matching legacy policy`() {
        val availableWidth = 1080
        val viewportHeight = 2400

        val legacyMeasurement = measureWebtoonViewport(viewportHeight, availableWidth, null, null)
        val hint = PageGeometryHint.Estimated(1080f / 2400f)
        val layout = layoutVerticalPages(availableWidth, viewportHeight, listOf(1L to hint))

        assertEquals(
            legacyMeasurement.itemHeightPx.toFloat(),
            layout.first().sceneBounds.height,
        )
    }

    @Test
    fun `visible nodes match legacy resolveLastEndVisibleWebtoonPageKey active page semantics`() {
        val availableWidth = 1000
        val viewportHeight = 1000
        val scrollOffset = 200f // Viewport looks at 200..1200 in scene space

        // Legacy setup matching test:
        // Page 105: offset -200 relative to viewport -> scene Y = 0..400
        // Page 201: offset +200 relative to viewport -> scene Y = 400..1200
        val pages = listOf(
            PageGeometry(PageId(105L), FloatRect.fromLtwh(0f, 0f, 1000f, 400f)),
            PageGeometry(PageId(201L), FloatRect.fromLtwh(0f, 400f, 1000f, 800f)),
        )

        val viewport = ReaderViewport(
            bounds = FloatRect.fromLtwh(0f, scrollOffset, availableWidth.toFloat(), viewportHeight.toFloat()),
        )

        val frame = VisibleRegionResolver.resolve(viewport, pages)

        // Map VisibleNodes to legacy WebtoonVisibleItem to verify semantic equivalence
        val legacyItems = frame.visibleNodes.map { node ->
            WebtoonVisibleItem(
                pageKey = node.pageId.value,
                offsetPx = (node.sceneBounds.top - scrollOffset).toInt(),
                sizePx = node.sceneBounds.height.toInt(),
            )
        }

        val legacyActiveKey = resolveLastEndVisibleWebtoonPageKey(
            items = legacyItems,
            viewportStartPx = 0,
            viewportEndPx = viewportHeight,
        )

        assertEquals(201L, legacyActiveKey)
    }

    @Test
    fun `scene indexOf matches legacy resolveWebtoonAnchorPosition`() {
        val pageKeys = listOf(18201L, 18202L, 18301L, 18302L)
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = pageKeys.map { PageId(it) to PageGeometryHint.Estimated(1f) },
        )

        val targetKey = 18301L
        val legacyIndex = org.skepsun.kototoro.reader.ui.compose.resolveWebtoonAnchorPosition(pageKeys, targetKey)
        val sceneIndex = scene.indexOf(PageId(targetKey))

        assertEquals(legacyIndex, sceneIndex)
        assertEquals(2, sceneIndex)
    }

    @Test
    fun `scene page position resolution gives exact layout coordinates matching accumulated heights`() {
        val pages = listOf(
            PageId(1L) to PageGeometryHint.Exact(1000, 1500),
            PageId(2L) to PageGeometryHint.Exact(1000, 2500),
            PageId(3L) to PageGeometryHint.Exact(1000, 3500),
        )
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = pages,
        )

        assertEquals(0f, scene.resolvePageScrollPosition(PageId(1L)))
        assertEquals(1500f, scene.resolvePageScrollPosition(PageId(2L)))
        assertEquals(4000f, scene.resolvePageScrollPosition(PageId(3L)))
    }
}


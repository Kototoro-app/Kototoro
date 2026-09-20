package org.skepsun.kototoro.reader.render.compose

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderFrame
import org.skepsun.kototoro.reader.core.ReaderProgressSnapshot
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.image.ImageSourceGeometry
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.ReaderImageLoadState
import org.skepsun.kototoro.reader.image.ReaderImagePipeline
import org.skepsun.kototoro.reader.image.TileGrid
import org.skepsun.kototoro.reader.image.TileKey
import org.skepsun.kototoro.reader.image.TileStore

class SceneImagePresentationCoordinatorTest {

    @Test
    fun `computeVisibleLogicalRect maps relative intersection to image coordinates accurately`() {
        val sceneBounds = FloatRect(100f, 200f, 900f, 1400f) // 800 x 1200
        val visibleRegion = FloatRect(100f, 200f, 500f, 800f) // half width, half height
        val imageWidth = 1600
        val imageHeight = 2400

        val logicalRect = SceneImagePresentationCoordinator.computeVisibleLogicalRect(
            nodeSceneBounds = sceneBounds,
            nodeVisibleRegion = visibleRegion,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )

        assertEquals(IntRect(0, 0, 800, 1200), logicalRect)
    }

    @Test
    fun `computeVisibleLogicalRect clamps to image bounds when partially out of range`() {
        val sceneBounds = FloatRect(0f, 0f, 1000f, 1000f)
        val visibleRegion = FloatRect(-50f, -50f, 1200f, 1200f)
        val imageWidth = 2000
        val imageHeight = 2000

        val logicalRect = SceneImagePresentationCoordinator.computeVisibleLogicalRect(
            nodeSceneBounds = sceneBounds,
            nodeVisibleRegion = visibleRegion,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )

        assertEquals(IntRect(0, 0, 2000, 2000), logicalRect)
    }

    @Test
    fun `computeVisibleBounds calculates horizontal scene viewport under 2x zoom`() {
        val visible = SceneImagePresentationCoordinator.computeVisibleBounds(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            scrollOffset = 2000f,
            isHorizontal = true,
            canvasScale = 2f,
            canvasOffsetX = 0f,
            canvasOffsetY = 0f,
            totalSceneExtent = 10000f,
            totalCrossExtent = 1500f,
        )

        // Center = (500, 750)
        // Zoom 2x: span is [250..750] horizontally, [375..1125] vertically
        // Offset = 2000
        assertEquals(2250f, visible.left)
        assertEquals(375f, visible.top)
        assertEquals(2750f, visible.right)
        assertEquals(1125f, visible.bottom)
    }

    @Test
    fun `computeVisibleBounds calculates vertical scene viewport under 2x zoom`() {
        val visible = SceneImagePresentationCoordinator.computeVisibleBounds(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            scrollOffset = 3000f,
            isHorizontal = false,
            canvasScale = 2f,
            canvasOffsetX = 0f,
            canvasOffsetY = 0f,
            totalSceneExtent = 10000f,
            totalCrossExtent = 1000f,
        )

        // Center = (500, 750)
        // Zoom 2x: span is [250..750] horizontally, [375..1125] vertically
        // Offset = 3000 vertically
        assertEquals(250f, visible.left)
        assertEquals(3375f, visible.top)
        assertEquals(750f, visible.right)
        assertEquals(4125f, visible.bottom)
}

    // ---------------------------------------------------------------------------------------------
    // Tile request routing through the ReaderImagePipeline seam
    // ---------------------------------------------------------------------------------------------

    /** Pure-JVM recording double: proves the coordinator only needs the pipeline CONTRACT. */
    private class RecordingPipeline(
        private val retained: Map<PageId, ReaderImageAsset> = emptyMap(),
    ) : ReaderImagePipeline {
        val tileRequests = mutableListOf<Pair<PageId, IntRect>>()

        override val assets: StateFlow<Map<PageId, ReaderImageAsset>> =
            MutableStateFlow(retained)
        override val loadStates: StateFlow<Map<PageId, ReaderImageLoadState>> =
            MutableStateFlow(emptyMap())
        override fun getCachedAsset(pageId: PageId): ReaderImageAsset? = retained[pageId]
        override fun updateResourceWindow(window: ReaderResourceWindow) = Unit
        override fun observeAsset(pageId: PageId): Flow<ReaderImageAsset?> = emptyFlow()
        override suspend fun retryAsset(pageId: PageId): ReaderImageAsset? = null
        override fun requestTiles(pageId: PageId, visibleRegion: IntRect, lookaheadRegion: IntRect?) {
            tileRequests += pageId to visibleRegion
        }
    }

    private object NoopTileStore : TileStore {
        override fun tile(key: TileKey) = null
        override fun addListener(listener: org.skepsun.kototoro.reader.image.TileStore.Listener) = Unit
        override fun removeListener(listener: org.skepsun.kototoro.reader.image.TileStore.Listener) = Unit
    }

    private fun tiledAsset(pageId: PageId): ReaderImageAsset.Tiled {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(2000, 3000)),
            tileDimension = IntSize(512, 512),
        )
        return ReaderImageAsset.Tiled(pageId = pageId, grid = grid, tileStore = NoopTileStore)
    }

    private fun frameOf(vararg nodes: VisibleNode): ReaderFrame =
        ReaderFrame(
            viewport = ReaderViewport(FloatRect(0f, 0f, 1000f, 1500f)),
            visibleNodes = nodes.toList(),
            readingDirection = SceneReadingDirection.TOP_TO_BOTTOM,
        )

    @Test
    fun `coordinateVisibleTiles routes tiled node requests through the pipeline contract`() {
        val asset = tiledAsset(PageId(1L))
        // Node fully visible at scale 2x against a 2000x3000 lattice.
        val node = VisibleNode(
            pageId = PageId(1L),
            sceneBounds = FloatRect(0f, 0f, 1000f, 1500f),
            visibleRegion = FloatRect(0f, 0f, 1000f, 1500f),
        )
        val pipeline = RecordingPipeline()

        SceneImagePresentationCoordinator.coordinateVisibleTiles(
            frame = frameOf(node),
            retainedAssets = mapOf(PageId(1L) to asset),
            pipeline = pipeline,
        )

        assertEquals(listOf(PageId(1L) to IntRect(0, 0, 2000, 3000)), pipeline.tileRequests)
    }

    @Test
    fun `coordinateVisibleTiles ignores non-tiled assets`() {
        val asset = ReaderImageAsset.Encoded(PageId(2L), "file:///page.png", "image/png")
        val node = VisibleNode(
            pageId = PageId(2L),
            sceneBounds = FloatRect(0f, 0f, 1000f, 1500f),
            visibleRegion = FloatRect(0f, 0f, 1000f, 1500f),
        )
        val pipeline = RecordingPipeline()

        SceneImagePresentationCoordinator.coordinateVisibleTiles(
            frame = frameOf(node),
            retainedAssets = mapOf(PageId(2L) to asset),
            pipeline = pipeline,
        )

        assertTrue(pipeline.tileRequests.isEmpty())
    }

    @Test
    fun `coordinateVisibleTiles skips zero-size and unretained nodes`() {
        val zeroSize = VisibleNode(
            pageId = PageId(1L),
            sceneBounds = FloatRect(0f, 0f, 0f, 0f),
            visibleRegion = FloatRect(0f, 0f, 0f, 0f),
        )
        val unretained = VisibleNode(
            pageId = PageId(9L),
            sceneBounds = FloatRect(0f, 0f, 1000f, 1500f),
            visibleRegion = FloatRect(0f, 0f, 1000f, 1500f),
        )
        val pipeline = RecordingPipeline()
        val retained = mapOf(PageId(1L) to tiledAsset(PageId(1L)))

        SceneImagePresentationCoordinator.coordinateVisibleTiles(
            frame = frameOf(zeroSize, unretained),
            retainedAssets = retained,
            pipeline = pipeline,
        )

        assertTrue(pipeline.tileRequests.isEmpty())
    }
}

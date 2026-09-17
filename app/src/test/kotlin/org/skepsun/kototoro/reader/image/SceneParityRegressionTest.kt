package org.skepsun.kototoro.reader.image

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.request.SuccessResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageGeometry
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchPriority
import org.skepsun.kototoro.reader.core.PrefetchReadiness
import org.skepsun.kototoro.reader.core.PrefetchRequest
import org.skepsun.kototoro.reader.core.ReaderCameraSnapshot
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.render.compose.AnimatedDrawBridge
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

@OptIn(ExperimentalCoroutinesApi::class)
class SceneParityRegressionTest {

    private val context = mockk<Context>(relaxed = true)
    private val resources = mockk<Resources>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val page1 = ReaderPage(
        id = 101L,
        url = "https://example.com/animated.gif",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 0,
        source = TestContentSource,
    )

    private val stripPage = ReaderPage(
        id = 102L,
        url = "https://example.com/long_strip.jpg",
        preview = null,
        headers = null,
        chapterId = 1L,
        index = 1,
        source = TestContentSource,
    )

    private class FakeComposePipeline : ComposeReaderImagePipeline {
        var stateToReturn: ComposeReaderImageState? = null
        var reportedDimensions: Triple<ReaderPage, Int, Int>? = null

        override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
            stateToReturn?.let { emit(it) }
        }

        override fun cachedState(pageKey: Long): ComposeReaderImageState? = stateToReturn

        override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) {
            reportedDimensions = Triple(page, width, height)
        }
    }

    private class FakeRegionDecodeSource(
        override val metadata: ImageSourceMetadata = ImageSourceMetadata(
            size = IntSize(800, 32000),
            mimeType = "image/jpeg",
            isAnimated = false,
        ),
        override val geometry: ImageSourceGeometry = ImageSourceGeometry(
            encodedSize = IntSize(800, 32000),
        ),
    ) : RegionDecodeSource {
        override suspend fun openSession(): TileDecodeSession = mockk(relaxed = true)
    }

    private class FakeRegionDecoderFactory(
        val sourceToReturn: FakeRegionDecodeSource = FakeRegionDecodeSource(),
    ) : RegionDecoderFactory {
        override suspend fun create(
            uri: Uri,
            isAnimatedHint: Boolean?,
            geometryOverride: ImageSourceGeometry?,
        ): RegionDecodeSource = sourceToReturn
    }

    abstract class AnimatableDrawable : Drawable(), Animatable

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.resources } returns resources
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `animated image bypasses tiling and returns Animated asset with lifecycle stop`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/sample.gif"

        val pipeline = FakeComposePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri, isAnimated = true)
        }

        val animDrawable = mockk<AnimatableDrawable>(relaxed = true)
        every { animDrawable.intrinsicWidth } returns 400
        every { animDrawable.intrinsicHeight } returns 600

        val coilResult = mockk<SuccessResult>()
        every { coilResult.image } returns animDrawable.asImage()
        coEvery { imageLoader.execute(any()) } returns coilResult

        val regionFactory = spyk(FakeRegionDecoderFactory())
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = regionFactory,
            tileManager = mockTileManager,
            pageLookup = { if (it.value == page1.readerKey) page1 else null },
        )

        val pageId = PageId(page1.readerKey)
        val asset = adapter.acquireAsset(pageId)

        assertNotNull(asset)
        assertTrue(asset is ReaderImageAsset.Animated)
        val animatedAsset = asset as ReaderImageAsset.Animated
        assertEquals(400, animatedAsset.width)
        assertEquals(600, animatedAsset.height)
        assertSame(animDrawable, animatedAsset.drawable)

        // Verifies tiling was NOT opened
        verify(exactly = 0) { mockTileManager.requestOverview(any(), any()) }

        // probeCachedDimensions returns exact dimensions for Animated
        val probedSize = adapter.probeCachedDimensions(pageId)
        assertEquals(IntSize(400, 600), probedSize)

        // Downgrading to source stops the animatable
        val downgradeWindow = ReaderResourceWindow(
            requests = listOf(
                PrefetchRequest(
                    pageId = pageId,
                    readiness = PrefetchReadiness.SOURCE_READY,
                    priority = PrefetchPriority.IMMEDIATE,
                ),
            ),
        )
        adapter.updateResourceWindow(downgradeWindow)
        verify { animDrawable.stop() }
    }

    @Test
    fun `animated draw bridge starts and stops based on visibility`() {
        val bridge = AnimatedDrawBridge()
        val pageId = PageId(101L)
        val animDrawable = mockk<AnimatableDrawable>(relaxed = true)
        every { animDrawable.isRunning } returns false

        bridge.register(pageId, animDrawable)

        // Page becomes visible -> starts animation
        bridge.updateVisiblePages(setOf(pageId))
        verify { animDrawable.start() }

        every { animDrawable.isRunning } returns true

        // Page goes off-screen -> stops animation
        bridge.updateVisiblePages(emptySet())
        verify { animDrawable.stop() }
    }

    @Test
    fun `preview asset does not satisfy authoritative presentation and does not distort dimensions`() {
        val pageId = PageId(101L)
        val mockBmp = mockk<ImageBitmap>()
        val preview = ReaderImageAsset.Preview(pageId, mockBmp)

        assertFalse(preview.isAuthoritativePresentation)

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = FakeComposePipeline(),
            imageLoader = imageLoader,
            scope = testScope,
            ioDispatcher = testDispatcher,
        )

        // Probe on preview returns null so scene layout is NOT corrupted
        val probed = adapter.probeCachedDimensions(pageId)
        assertNull(probed)
    }

    @Test
    fun `multi-LOD progressive replacement maintains base layer while requesting target layer`() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///storage/long_strip.jpg"

        val pipeline = FakeComposePipeline().apply {
            stateToReturn = ComposeReaderImageState.OriginalReady(uri, isAnimated = false)
        }

        val highResSource = FakeRegionDecodeSource(
            metadata = ImageSourceMetadata(
                size = IntSize(1600, 32000),
                mimeType = "image/jpeg",
                isAnimated = false,
            ),
            geometry = ImageSourceGeometry(
                encodedSize = IntSize(1600, 32000),
            ),
        )
        val regionFactory = FakeRegionDecoderFactory(highResSource)
        val mockTileManager = mockk<ReaderTileManager>(relaxed = true)
        val planner = DecodePlanner()

        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = imageLoader,
            scope = this,
            ioDispatcher = testDispatcher,
            regionDecoderFactory = regionFactory,
            decodePlanner = planner,
            tileManager = mockTileManager,
            viewportSizeProvider = { IntSize(800, 1200) },
            pageLookup = { if (it.value == stripPage.readerKey) stripPage else null },
        )

        val pageId = PageId(stripPage.readerKey)
        val initialAsset = adapter.acquireAsset(pageId)

        assertNotNull(initialAsset)
        assertTrue(initialAsset is ReaderImageAsset.Tiled)
        val tiled = initialAsset as ReaderImageAsset.Tiled
        assertNull(tiled.target)
        val baseSampleSize = tiled.base.sampleSize
        assertEquals(2, baseSampleSize)

        // Set up scene
        val scene = VerticalReaderScene(
            availableWidth = 800,
            defaultViewportHeight = 1200,
            initialPages = listOf(pageId to PageGeometryHint.Exact(1600, 32000)),
        )

        // Zoom to 2.0x -> camera settle evaluates LOD
        val snapshot = ReaderCameraSnapshot(
            scale = 2.0f,
            visibleBoundsInScene = FloatRect(0f, 0f, 800f, 2000f),
        )
        adapter.onCameraSettled(snapshot, scene)

        val settledAsset = adapter.assets.value[pageId] as? ReaderImageAsset.Tiled
        assertNotNull(settledAsset)
        // Base layer is retained
        assertEquals(baseSampleSize, settledAsset!!.base.sampleSize)
        // Target layer is created with higher LOD (smaller sampleSize)
        val targetLayer = settledAsset.target
        assertNotNull(targetLayer)
        assertTrue(targetLayer!!.sampleSize < baseSampleSize)

        // Requesting tiles while zoomed forwards requests for both base and target grids
        adapter.requestTiles(pageId, IntRect(0, 0, 800, 1000))
        verify { mockTileManager.requestTiles(settledAsset.base.grid, any(), any()) }
        verify { mockTileManager.requestTiles(targetLayer.grid, any(), any()) }

        // Zoom-out to 1.0x -> releases target tiles and drops target layer
        val zoomOutSnapshot = ReaderCameraSnapshot(
            scale = 1.0f,
            visibleBoundsInScene = FloatRect(0f, 0f, 800f, 2400f),
        )
        adapter.onCameraSettled(zoomOutSnapshot, scene)

        val zoomOutAsset = adapter.assets.value[pageId] as? ReaderImageAsset.Tiled
        assertNotNull(zoomOutAsset)
        assertNull(zoomOutAsset!!.target)
        verify { mockTileManager.releaseTiles(any()) }
    }
}

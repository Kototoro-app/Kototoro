package org.skepsun.kototoro.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.image.AvifAnimatedDrawable
import org.skepsun.kototoro.core.image.AvifImageDecoder
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderFrame
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.render.compose.AnimatedDrawBridge
import org.skepsun.kototoro.reader.render.compose.drawFrameNodes
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import java.io.File

/** Exercises production Coil/libavif decoding, scene acquisition and Draw-phase playback. */
@RunWith(AndroidJUnit4::class)
class SceneAnimatedImageTest {

    @Test
    fun scenePreservesAvifAnimationWithoutCropping() = verifySceneAnimation(isCropEnabled = false)

    @Test
    fun croppingDoesNotFlattenAvifAnimation() = verifySceneAnimation(isCropEnabled = true)

    @Test
    fun decoderDetectedAnimationSurvivesMissingMetadataHint() = verifySceneAnimation(isAnimatedHint = false)

    @Test
    fun newlyVisibleAnimationStartsOnItsFirstDraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = syntheticAnimation()
            val id = PageId(123L)
            val rect = FloatRect.fromLtwh(0f, 0f, 32f, 32f)
            val frame = ReaderFrame(ReaderViewport(rect), listOf(VisibleNode(id, rect, rect)))
            val asset = ReaderImageAsset.Animated(id, drawable, 32, 32)
            val bridge = AnimatedDrawBridge()
            val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                CanvasDrawScope().draw(
                    Density(1f),
                    LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(Canvas(target)),
                    Size(32f, 32f),
                ) {
                    drawFrameNodes(
                        frame = frame,
                        placeholderColor = androidx.compose.ui.graphics.Color.Black,
                        readerAssetProvider = { asset },
                        animatedBridge = bridge,
                    )
                }
                assertTrue("First draw must start playback without another scroll or draw", drawable.isRunning)
            } finally {
                bridge.stopAll()
                drawable.release()
                target.recycle()
            }
        }
    }

    @Test
    fun offscreenAnimationIsDetachedAndCanResumeWhenRegisteredAgain() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bridge = AnimatedDrawBridge(autoUpdateVisiblePages = false)
            val drawable = syntheticAnimation()
            val id = PageId(123L)
            try {
                bridge.updateVisiblePages(setOf(id))
                bridge.register(id, drawable)
                assertTrue(drawable.isRunning)
                bridge.updateVisiblePages(emptySet())
                assertFalse(drawable.isRunning)
                assertNull("Offscreen frames must not be retained by the bridge callback", drawable.callback)
                bridge.updateVisiblePages(setOf(id))
                bridge.register(id, drawable)
                assertTrue(drawable.isRunning)
            } finally {
                bridge.stopAll()
                drawable.release()
            }
        }
    }

    @Test
    fun registeringAnAlreadyRunningDrawableWhilePausedStopsPlayback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val drawable = syntheticAnimation()
            val bridge = AnimatedDrawBridge()
            val id = PageId(123L)
            try {
                bridge.setPlaybackEnabled(false)
                bridge.updateVisiblePages(setOf(id))
                drawable.start()
                bridge.register(id, drawable)
                assertFalse(drawable.isRunning)
                bridge.setPlaybackEnabled(true)
                assertTrue(drawable.isRunning)
            } finally {
                bridge.stopAll()
                drawable.release()
            }
        }
    }

    @Test
    fun animationEvictionReleasesItsFrames() = withSceneAnimation { adapter, drawable ->
        adapter.updateResourceWindow(ReaderResourceWindow(emptyList()))
        val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                drawable.setBounds(0, 0, 32, 32)
                drawable.draw(Canvas(target))
            }
            assertEquals(
                "An evicted AVIF must no longer draw its released frames",
                Color.TRANSPARENT,
                target.getPixel(16, 16),
            )
        } finally {
            target.recycle()
        }
    }

    @Test
    fun closingSceneReleasesAnimationAndPreventsRestart() = withSceneAnimation { adapter, drawable ->
        adapter.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            drawable.start()
            assertFalse("Disposed scenes must release animation frames", drawable.isRunning)
        }
        assertTrue(adapter.assets.value.isEmpty())
    }

    @Test
    fun staticPageSplitStillAppliesItsBitmapTransformation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "scene-static-split.png")
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        Canvas(bitmap).drawRect(16f, 0f, 32f, 16f, android.graphics.Paint().apply { color = Color.BLUE })
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = Uri.fromFile(fixture)
        val page = ReaderPage(1L, uri.toString(), null, null, 1L, 0, LocalMangaSource, ReaderPageSplit.RIGHT)
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = flowOf(ComposeReaderImageState.OriginalReady(uri))
            override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) = Unit
        }
        val loader = imageLoader()
        val adapter = KototoroImagePipelineAdapter(context, pipeline, loader, this, pageLookup = { page })
        try {
            val asset = adapter.acquireAsset(PageId(page.readerKey))
            assertTrue("Static pages must retain bitmap transformations", asset is ReaderImageAsset.ComposeImage)
            val image = (asset as ReaderImageAsset.ComposeImage).imageBitmap
            assertEquals(16, image.width)
            assertEquals(16, image.height)
        } finally {
            adapter.close()
            loader.shutdown()
        }
    }

    @Test
    fun sampledAvifRequestKeepsBothFrames() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = avifFixture()
        val loader = imageLoader()
        var drawable: AvifAnimatedDrawable? = null
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(fixture).size(8, 8).build())
            assertTrue(result.toString(), result is SuccessResult)
            result as SuccessResult
            drawable = result.image.asDrawable(context.resources) as AvifAnimatedDrawable
            assertEquals(8, drawable.intrinsicWidth)
            assertEquals(8, drawable.intrinsicHeight)
            assertTrue(result.isSampled)
            assertChangingFrames(drawable)
        } finally {
            instrumentation.runOnMainSync { drawable?.release() }
            loader.shutdown()
        }
    }

    private fun verifySceneAnimation(isCropEnabled: Boolean = false, isAnimatedHint: Boolean = true) =
        withSceneAnimation(isCropEnabled, isAnimatedHint) { _, drawable -> assertChangingFrames(drawable) }

    private fun withSceneAnimation(
        isCropEnabled: Boolean = false,
        isAnimatedHint: Boolean = true,
        check: (KototoroImagePipelineAdapter, AvifAnimatedDrawable) -> Unit,
    ) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = avifFixture()
        val loader = imageLoader()
        val uri = Uri.fromFile(fixture)
        val page = ReaderPage(1L, uri.toString(), null, null, 1L, 0, LocalMangaSource)
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(uri, isAnimated = isAnimatedHint))

            override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) = Unit
        }
        val adapter = KototoroImagePipelineAdapter(
            context = context,
            composePipeline = pipeline,
            imageLoader = loader,
            scope = this,
            isCropEnabled = isCropEnabled,
            pageLookup = { page },
        )
        var drawable: AvifAnimatedDrawable? = null
        try {
            val asset = adapter.acquireAsset(PageId(page.readerKey))
            assertTrue("Expected animated asset, got $asset", asset is ReaderImageAsset.Animated)
            drawable = (asset as ReaderImageAsset.Animated).drawable as? AvifAnimatedDrawable
            assertTrue("Scene must preserve the multi-frame drawable", drawable != null)
            check(adapter, requireNotNull(drawable))
        } finally {
            adapter.updateResourceWindow(ReaderResourceWindow(emptyList()))
            instrumentation.runOnMainSync { drawable?.release() }
            loader.shutdown()
        }
    }

    private fun imageLoader(): ImageLoader = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext)
        .memoryCache(null)
        .diskCache(null)
        .components {
            add(AnimatedImageDecoder.Factory())
            add(AvifImageDecoder.Factory())
        }
        .build()

    private fun avifFixture(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return File(instrumentation.targetContext.cacheDir, "scene-animated-image.avif").apply {
            instrumentation.context.assets.open("reader/two-frame.avif").use { input ->
                outputStream().use { input.copyTo(it) }
            }
            assertTrue(BitmapDecoderCompat.isAnimated(this))
        }
    }

    private fun syntheticAnimation(): AvifAnimatedDrawable = AvifAnimatedDrawable(
        listOf(Color.RED, Color.BLUE).map { color ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        },
        longArrayOf(60_000, 60_000),
        -1,
    )

    private fun assertChangingFrames(drawable: Drawable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, 32, 32)
                drawable.draw(Canvas(target))
                val first = target.getPixel(16, 16)
                (drawable as AvifAnimatedDrawable).let { it.start(); it.run() }
                drawable.draw(Canvas(target))
                assertNotEquals("Advancing an AVIF frame must change its pixels", first, target.getPixel(16, 16))
            } finally {
                target.recycle()
            }
        }
    }
}

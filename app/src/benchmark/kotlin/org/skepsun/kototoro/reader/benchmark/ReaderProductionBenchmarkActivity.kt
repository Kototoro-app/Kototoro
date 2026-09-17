package org.skepsun.kototoro.reader.benchmark

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Trace
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntRect
import coil3.ImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeSceneWebtoonReader
import org.skepsun.kototoro.reader.ui.compose.ComposeWebtoonReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * Production-equivalent reader benchmark fixture for Phase 0B.
 *
 * Runs deterministic local disk sources through real Coil decoding, transformations,
 * resource lifecycle states (SOURCE_READY <-> PRESENTATION_READY), and presentation
 * pipelines comparing Legacy ComposeWebtoonReader vs ComposeSceneWebtoonReader.
 */
class ReaderProductionBenchmarkActivity : ComponentActivity() {

    private lateinit var readyView: View
    private lateinit var pipeline: BenchmarkProductionImagePipeline
    private lateinit var pages: List<ReaderPage>
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestHighRefreshRate()

        // 1. Prepare deterministic 72-page fixture
        pages = getOrCreateFixture(this)
        pipeline = BenchmarkProductionImagePipeline(pages)

        // 2. Setup view hierarchy with explicit Android View readiness marker
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        readyView = android.widget.TextView(this).apply {
            id = R.id.benchmark_ready
            text = "benchmark_ready"
            contentDescription = "benchmark_ready"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val backend = intent.getStringExtra(EXTRA_BACKEND) ?: BACKEND_SCENE_WEBTOON
        android.util.Log.e("BenchmarkActivity", "onCreate starting for backend=$backend")

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                MaterialTheme {
                    val imageLoader = SingletonImageLoader.get(this@ReaderProductionBenchmarkActivity)

                    BenchmarkReaderContent(
                        backend = backend,
                        pages = pages,
                        imageLoader = imageLoader,
                        pipeline = pipeline,
                    )
                }
            }
        }

        rootLayout.addView(composeView)
        rootLayout.addView(readyView)
        setContentView(rootLayout)

        // 3. Monitor initial presentation readiness to signal BENCHMARK_READY
        monitorInitialReadiness()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayListener?.let {
            val dm = getSystemService(DisplayManager::class.java)
            dm?.unregisterDisplayListener(it)
        }
    }

    private fun monitorInitialReadiness() {
        android.util.Log.e("BenchmarkActivity", "monitorInitialReadiness entered")
        val checkAndSignal = {
            android.util.Log.e(
                "BenchmarkActivity",
                "checkAndSignal decodedKeys=${pipeline.decodedPageKeys} readyVis=${readyView.visibility}",
            )
            if (pipeline.decodedPageKeys.isNotEmpty() && readyView.visibility != View.VISIBLE) {
                // Wait for two Choreographer frames to ensure textures are presented to display
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback {
                        readyView.visibility = View.VISIBLE
                        sendBroadcast(Intent(ACTION_BENCHMARK_READY))
                        android.util.Log.e(
                            "BenchmarkActivity",
                            "benchmark_ready signaled VISIBLE and broadcast sent, decodedKeys=${pipeline.decodedPageKeys}",
                        )
                    }
                }
            }
        }
        pipeline.onPageDecoded = {
            runOnUiThread {
                checkAndSignal()
            }
        }
        checkAndSignal()
    }

    @Composable
    private fun BenchmarkReaderContent(
        backend: String,
        pages: List<ReaderPage>,
        imageLoader: ImageLoader,
        pipeline: BenchmarkProductionImagePipeline,
    ) {
        if (backend == BACKEND_LEGACY_WEBTOON) {
            ComposeWebtoonReader(
                pages = pages,
                initialPage = 0,
                initialScroll = 0,
                imageLoader = imageLoader,
                imagePipeline = pipeline,
                onPagesChanged = { _, _, _ -> },
                onInternalScrollChanged = { _, _ -> },
                isAnimationEnabled = false,
                bitmapConfig = Bitmap.Config.ARGB_8888,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BENCHMARK_SURFACE_TAG),
            )
        } else {
            ComposeSceneWebtoonReader(
                pages = pages,
                initialPage = 0,
                initialScroll = 0,
                imageLoader = imageLoader,
                imagePipeline = pipeline,
                onPagesChanged = { _, _, _ -> },
                onInternalScrollChanged = { _, _ -> },
                isAnimationEnabled = false,
                bitmapConfig = Bitmap.Config.ARGB_8888,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BENCHMARK_SURFACE_TAG),
            )
        }
    }

    private fun requestHighRefreshRate() {
        val targetDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val highRateMode = targetDisplay?.supportedModes?.firstOrNull { it.refreshRate >= 119f }
        window.attributes = window.attributes.apply {
            if (highRateMode != null) {
                preferredDisplayModeId = highRateMode.modeId
            }
            preferredRefreshRate = 120f
        }
        updateRefreshRateCounter(targetDisplay?.refreshRate ?: 60f)

        val dm = getSystemService(DisplayManager::class.java)
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == targetDisplay?.displayId) {
                    updateRefreshRateCounter(targetDisplay?.refreshRate ?: 60f)
                }
            }
        }
        dm?.registerDisplayListener(displayListener, null)
    }

    private fun updateRefreshRateCounter(rate: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("Reader.ActualRefreshRateHz", rate.toLong())
        }
    }

    companion object {
        const val ACTION_BENCHMARK_READY = "org.skepsun.kototoro.BENCHMARK_READY"
        const val EXTRA_BACKEND = "backend"
        const val BACKEND_LEGACY_WEBTOON = "legacy_webtoon"
        const val BACKEND_SCENE_WEBTOON = "scene_webtoon"
        const val BENCHMARK_SURFACE_TAG = "reader_benchmark_surface"

        private const val FIXTURE_VERSION = "v1"
        private const val PAGE_COUNT = 72

        private val DIMENSION_HEIGHTS = intArrayOf(
            1440, 2400, 1280, 800, 950, 1750, 2100, 3200,
            1080, 2560, 1350, 1920,
        )
        private val DIMENSION_WIDTHS = intArrayOf(
            800, 800, 800, 1200, 800, 800, 800, 800,
            800, 800, 800, 800,
        )

        fun getOrCreateFixture(context: Context): List<ReaderPage> {
            val fixtureDir = File(context.filesDir, "reader-benchmark/$FIXTURE_VERSION")
            if (!fixtureDir.exists()) {
                fixtureDir.mkdirs()
            }

            val pages = ArrayList<ReaderPage>(PAGE_COUNT)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (index in 0 until PAGE_COUNT) {
                val file = File(fixtureDir, "page_%03d.jpg".format(index))
                if (!file.exists() || file.length() == 0L) {
                    val patternIndex = index % DIMENSION_HEIGHTS.size
                    val width = DIMENSION_WIDTHS[patternIndex]
                    val height = DIMENSION_HEIGHTS[patternIndex]

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = AndroidCanvas(bitmap)

                    // Base background
                    paint.color = AndroidColor.rgb(
                        (30 + index * 17) % 200,
                        (40 + index * 23) % 200,
                        (50 + index * 31) % 200,
                    )
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                    // High frequency stripes and texture
                    val stripeCount = height / 60
                    for (s in 0 until stripeCount) {
                        paint.color = AndroidColor.argb(
                            180,
                            (255 - index * 7 - s * 3) and 0xFF,
                            (200 - index * 11 + s * 5) and 0xFF,
                            (150 + index * 13 - s * 2) and 0xFF,
                        )
                        val top = s * 60f
                        canvas.drawRect(0f, top, width.toFloat(), top + 24f, paint)
                    }

                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    bitmap.recycle()
                }

                pages.add(
                    ReaderPage(
                        id = index.toLong(),
                        url = Uri.fromFile(file).toString(),
                        preview = null,
                        headers = null,
                        chapterId = 1L,
                        index = index,
                        source = BenchmarkContentSource,
                    ),
                )
            }
            return pages
        }
    }
}

class BenchmarkProductionImagePipeline(
    pages: List<ReaderPage>,
) : ComposeReaderImagePipeline {

    private val readyStates = pages.associate { page ->
        page.readerKey to ComposeReaderImageState.OriginalReady(Uri.parse(page.url))
    }
    val decodedPageKeys: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    var onPageDecoded: ((Long) -> Unit)? = null

    override fun observe(page: ReaderPage, force: Boolean): Flow<ComposeReaderImageState> = flow {
        emit(ComposeReaderImageState.LoadingOriginal)
        readyStates[page.readerKey]?.let { emit(it) }
    }

    override fun cachedState(pageKey: Long): ComposeReaderImageState? = readyStates[pageKey]

    override suspend fun getTrimmedBounds(uri: Uri): IntRect? = null

    override fun onImageDecoded(page: ReaderPage, width: Int, height: Int) {
        decodedPageKeys.add(page.readerKey)
        onPageDecoded?.invoke(page.readerKey)
    }
}

private object BenchmarkContentSource : ContentSource {
    override val name: String = "Benchmark"
    override val locale: String = "en"
    override val contentType: ContentType = ContentType.MANGA
}

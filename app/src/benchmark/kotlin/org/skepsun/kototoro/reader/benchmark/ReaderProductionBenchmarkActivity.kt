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
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.compose.ComposePagedReader
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeScenePagedReader
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

        val backend = intent.getStringExtra(EXTRA_BACKEND) ?: BACKEND_SCENE_WEBTOON
        val fixtureMode = intent.getStringExtra(EXTRA_FIXTURE_MODE) ?: FIXTURE_MODE_STANDARD
        val animation = resolveBenchmarkAnimation(intent.getStringExtra(EXTRA_ANIMATION))
        val isDoublePage = intent.getBooleanExtra(EXTRA_DOUBLE_PAGE, false)
        val zoomMode = resolveBenchmarkZoomMode(intent.getStringExtra(EXTRA_ZOOM_MODE))
        val defaultScale = intent.getFloatExtra(EXTRA_DEFAULT_SCALE, 1f)
        android.util.Log.e(
            "BenchmarkActivity",
            "onCreate starting for backend=$backend, fixtureMode=$fixtureMode, animation=$animation, " +
                "doublePage=$isDoublePage, zoomMode=$zoomMode, defaultScale=$defaultScale",
        )

        // 1. Prepare deterministic fixture
        pages = getOrCreateFixture(this, fixtureMode)
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
                        animation = animation,
                        isDoublePage = isDoublePage,
                        zoomMode = zoomMode,
                        defaultScale = defaultScale,
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

    @Volatile
    private var visiblePageKeys: Set<Long> = emptySet()
    private var checkAndSignalAction: (() -> Unit)? = null

    override fun onDestroy() {
        super.onDestroy()
        displayListener?.let {
            val dm = getSystemService(DisplayManager::class.java)
            dm?.unregisterDisplayListener(it)
        }
    }

    private fun monitorInitialReadiness() {
        val checkAndSignal = {
            val isReady = if (visiblePageKeys.isNotEmpty()) {
                pipeline.decodedPageKeys.containsAll(visiblePageKeys)
            } else {
                pipeline.decodedPageKeys.isNotEmpty()
            }
            if (isReady && readyView.visibility != View.VISIBLE) {
                // Wait for two Choreographer frames to ensure textures are presented to display
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback {
                        readyView.visibility = View.VISIBLE
                        sendBroadcast(Intent(ACTION_BENCHMARK_READY))
                        android.util.Log.e(
                            "BenchmarkActivity",
                            "benchmark_ready signaled VISIBLE, visible=$visiblePageKeys, decoded=${pipeline.decodedPageKeys}",
                        )
                    }
                }
            }
        }
        checkAndSignalAction = checkAndSignal
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
        animation: ReaderAnimation,
        isDoublePage: Boolean,
        zoomMode: ZoomMode,
        defaultScale: Float,
    ) {
        val onVisiblePagesChanged: (Long, Long, Long) -> Unit = androidx.compose.runtime.remember(pages) {
            { lowerKey, upperKey, _ ->
                val lowerIdx = pages.indexOfFirst { it.readerKey == lowerKey }
                val upperIdx = pages.indexOfFirst { it.readerKey == upperKey }
                if (lowerIdx in pages.indices && upperIdx in pages.indices) {
                    val range = minOf(lowerIdx, upperIdx)..maxOf(lowerIdx, upperIdx)
                    visiblePageKeys = range.map { pages[it].readerKey }.toSet()
                } else {
                    visiblePageKeys = setOf(lowerKey, upperKey)
                }
                runOnUiThread {
                    checkAndSignalAction?.invoke()
                }
            }
        }

        if (backend == BACKEND_LEGACY_WEBTOON) {
            ComposeWebtoonReader(
                pages = pages,
                initialPage = 0,
                initialScroll = 0,
                imageLoader = imageLoader,
                imagePipeline = pipeline,
                onPagesChanged = onVisiblePagesChanged,
                onInternalScrollChanged = { _, _ -> },
                isAnimationEnabled = false,
                bitmapConfig = Bitmap.Config.ARGB_8888,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BENCHMARK_SURFACE_TAG),
            )
        } else if (backend == BACKEND_LEGACY_PAGED) {
            ComposePagedReader(
                pages = pages,
                initialPage = 0,
                mode = ReaderMode.STANDARD,
                imageLoader = imageLoader,
                imagePipeline = pipeline,
                onPageChanged = { page -> onVisiblePagesChanged(page.readerKey, page.readerKey, page.readerKey) },
                isAnimationEnabled = animation != ReaderAnimation.NONE,
                pageAnimation = animation,
                zoomMode = zoomMode,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BENCHMARK_SURFACE_TAG),
            )
        } else if (backend == BACKEND_SCENE_PAGED) {
            ComposeScenePagedReader(
                pages = pages,
                initialPage = 0,
                isDoublePage = isDoublePage,
                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                imageLoader = imageLoader,
                imagePipeline = pipeline,
                onPagesChanged = onVisiblePagesChanged,
                isAnimationEnabled = animation != ReaderAnimation.NONE,
                pageAnimation = animation,
                zoomMode = zoomMode,
                defaultScale = defaultScale,
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
                onPagesChanged = onVisiblePagesChanged,
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
        const val EXTRA_FIXTURE_MODE = "fixture_mode"
        const val EXTRA_ANIMATION = "animation"
        const val EXTRA_DOUBLE_PAGE = "double_page"
        const val EXTRA_ZOOM_MODE = "zoom_mode"
        const val EXTRA_DEFAULT_SCALE = "default_scale"
        const val BACKEND_LEGACY_WEBTOON = "legacy_webtoon"
        const val BACKEND_SCENE_WEBTOON = "scene_webtoon"
        const val BACKEND_LEGACY_PAGED = "legacy_paged"
        const val BACKEND_SCENE_PAGED = "scene_paged"
        const val BENCHMARK_SURFACE_TAG = "reader_benchmark_surface"

        const val FIXTURE_MODE_STANDARD = "standard"
        const val FIXTURE_MODE_ULTRA_LONG = "ultra_long"

        /** Portrait manga pages, sized for the discrete paged matrix. */
        const val FIXTURE_MODE_PAGED = "paged"

        /** Oversized single pages (6000x9000) for the large-image paged scenario. */
        const val FIXTURE_MODE_PAGED_LARGE = "paged_large"

        /**
         * Long chapters (improvement plan section 4.2): the same portrait pages as [FIXTURE_MODE_PAGED]
         * but with 50 / 500 / 5000 entries in the chapter, so total page count is the only variable.
         * The files themselves come from a bounded pool, because what scales with chapter length is
         * the metadata and the window queries over it, not the number of distinct images on disk.
         */
        const val FIXTURE_MODE_LONG_CHAPTER_50 = "long_chapter_50"
        const val FIXTURE_MODE_LONG_CHAPTER_500 = "long_chapter_500"
        const val FIXTURE_MODE_LONG_CHAPTER_5000 = "long_chapter_5000"

        fun resolveLongChapterPageCount(mode: String): Int? = when (mode) {
            FIXTURE_MODE_LONG_CHAPTER_50 -> 50
            FIXTURE_MODE_LONG_CHAPTER_500 -> 500
            FIXTURE_MODE_LONG_CHAPTER_5000 -> 5000
            else -> null
        }

        /** Maps the benchmark's animation extra onto the persisted reader preference values. */
        fun resolveBenchmarkAnimation(value: String?): ReaderAnimation = when (value?.lowercase()) {
            null, "", "default" -> ReaderAnimation.DEFAULT
            "none" -> ReaderAnimation.NONE
            "advanced" -> ReaderAnimation.ADVANCED
            "simulation" -> ReaderAnimation.SIMULATION
            else -> ReaderAnimation.valueOf(value.uppercase())
        }

        private fun resolveBenchmarkZoomMode(value: String?): ZoomMode = when (value?.lowercase()) {
            null, "", "fit_center" -> ZoomMode.FIT_CENTER
            "fit_width" -> ZoomMode.FIT_WIDTH
            "fit_height" -> ZoomMode.FIT_HEIGHT
            "keep_start", "original" -> ZoomMode.KEEP_START
            else -> ZoomMode.valueOf(value.uppercase())
        }

        private const val FIXTURE_VERSION_STANDARD = "v1"
        private const val FIXTURE_VERSION_ULTRA_LONG = "v1_ultra_long"
        private const val PAGE_COUNT_STANDARD = 72
        private const val PAGE_COUNT_ULTRA_LONG = 12

        private val DIMENSION_HEIGHTS_STANDARD = intArrayOf(
            1440, 2400, 1280, 800, 950, 1750, 2100, 3200,
            1080, 2560, 1350, 1920,
        )
        private val DIMENSION_WIDTHS_STANDARD = intArrayOf(
            800, 800, 800, 1200, 800, 800, 800, 800,
            800, 800, 800, 800,
        )

        private val DIMENSION_HEIGHTS_ULTRA_LONG = intArrayOf(
            12000, 20000, 16000, 30000, 40000, 15000,
            25000, 35000, 18000, 28000, 14000, 40000,
        )
        private const val DIMENSION_WIDTH_ULTRA_LONG = 1080

        private val DIMENSION_HEIGHTS_PAGED = intArrayOf(1200, 1800, 1200, 1500)
        private val DIMENSION_WIDTHS_PAGED = intArrayOf(800)

        private val DIMENSION_HEIGHTS_PAGED_LARGE = intArrayOf(9000)
        private val DIMENSION_WIDTHS_PAGED_LARGE = intArrayOf(6000)

        private const val FIXTURE_VERSION_PAGED = "v1_paged"
        private const val FIXTURE_VERSION_PAGED_LARGE = "v1_paged_large"
        private const val PAGE_COUNT_PAGED = 24
        private const val PAGE_COUNT_PAGED_LARGE = 8

        fun getOrCreateFixture(
            context: Context,
            mode: String = FIXTURE_MODE_STANDARD,
        ): List<ReaderPage> {
            val version = when (mode) {
                FIXTURE_MODE_ULTRA_LONG -> FIXTURE_VERSION_ULTRA_LONG
                FIXTURE_MODE_PAGED -> FIXTURE_VERSION_PAGED
                FIXTURE_MODE_PAGED_LARGE -> FIXTURE_VERSION_PAGED_LARGE
                else -> FIXTURE_VERSION_STANDARD
            }
            val pageCount = when (mode) {
                FIXTURE_MODE_ULTRA_LONG -> PAGE_COUNT_ULTRA_LONG
                FIXTURE_MODE_PAGED -> PAGE_COUNT_PAGED
                FIXTURE_MODE_PAGED_LARGE -> PAGE_COUNT_PAGED_LARGE
                else -> PAGE_COUNT_STANDARD
            }
            val heights = when (mode) {
                FIXTURE_MODE_ULTRA_LONG -> DIMENSION_HEIGHTS_ULTRA_LONG
                FIXTURE_MODE_PAGED -> DIMENSION_HEIGHTS_PAGED
                FIXTURE_MODE_PAGED_LARGE -> DIMENSION_HEIGHTS_PAGED_LARGE
                else -> DIMENSION_HEIGHTS_STANDARD
            }
            val widths = when (mode) {
                FIXTURE_MODE_ULTRA_LONG -> intArrayOf(DIMENSION_WIDTH_ULTRA_LONG)
                FIXTURE_MODE_PAGED -> DIMENSION_WIDTHS_PAGED
                FIXTURE_MODE_PAGED_LARGE -> DIMENSION_WIDTHS_PAGED_LARGE
                else -> DIMENSION_WIDTHS_STANDARD
            }
            val longChapterCount = resolveLongChapterPageCount(mode)
            val fixtureDir = File(context.filesDir, "reader-benchmark/$version")
            if (!fixtureDir.exists()) {
                fixtureDir.mkdirs()
            }
            if (longChapterCount != null) {
                return createLongChapterPages(context, longChapterCount)
            }

            val pages = ArrayList<ReaderPage>(pageCount)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (index in 0 until pageCount) {
                val file = File(fixtureDir, "page_%03d.jpg".format(index))
                if (!file.exists() || file.length() == 0L) {
                    val width = widths[index % widths.size]
                    val height = heights[index % heights.size]

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

        /**
         * A chapter of [pageCount] pages drawn from a bounded pool of real fixture files.
         *
         * Total page count is the variable under test, so the images must not be: 5000 distinct JPEGs
         * would cost minutes of fixture generation and gigabytes of storage while measuring the same
         * thing. Page identity still differs per entry (index and id), which is what the window
         * queries and the asset cache key on, so the pool stays an implementation detail of the
         * fixture rather than a change to what is measured.
         */
        private fun createLongChapterPages(context: Context, pageCount: Int): List<ReaderPage> {
            val pool = getOrCreateFixture(context, FIXTURE_MODE_PAGED)
            val pages = ArrayList<ReaderPage>(pageCount)
            for (index in 0 until pageCount) {
                val pooled = pool[index % pool.size]
                pages.add(
                    ReaderPage(
                        id = index.toLong(),
                        url = pooled.url,
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

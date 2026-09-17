package org.skepsun.kototoro.reader.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.render.canvas.AndroidViewSceneView
import org.skepsun.kototoro.reader.render.compose.ComposeSceneRenderer

/** Release-equivalent deterministic renderer fixture used only by the Macrobenchmark target. */
class ReaderRendererBenchmarkActivity : ComponentActivity() {

    private val fixture by lazy(::createFixture)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backend = intent.getStringExtra(EXTRA_BACKEND) ?: BACKEND_COMPOSE_SCENE
        setContent {
            MaterialTheme {
                when (backend) {
                    BACKEND_LAZY -> LazyRenderer(fixture)
                    BACKEND_VIEW_SCENE -> ViewSceneRenderer(fixture)
                    else -> ComposeScene(fixture)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fixture.bitmaps.forEach(Bitmap::recycle)
    }

    @Composable
    private fun LazyRenderer(fixture: ReaderBenchmarkFixture) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(BENCHMARK_SURFACE_TAG),
        ) {
            items(fixture.pages, key = BenchmarkPage::id) { page ->
                val bitmap = fixture.bitmaps[page.assetIndex]
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height),
                )
            }
        }
    }

    @Composable
    private fun ComposeScene(fixture: ReaderBenchmarkFixture) {
        val imageBitmaps = remember(fixture) { fixture.bitmaps.map(Bitmap::asImageBitmap) }
        ComposeSceneRenderer(
            scene = fixture.scene,
            placeholderColor = Color.DarkGray,
            assetProvider = { pageId ->
                fixture.pagesById[pageId]?.let { imageBitmaps[it.assetIndex] }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag(BENCHMARK_SURFACE_TAG),
        )
    }

    @Composable
    private fun ViewSceneRenderer(fixture: ReaderBenchmarkFixture) {
        AndroidView(
            factory = { context ->
                AndroidViewSceneView(context).apply {
                    scene = fixture.scene
                    assetProvider = { pageId ->
                        fixture.pagesById[pageId]?.let { fixture.bitmaps[it.assetIndex] }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag(BENCHMARK_SURFACE_TAG),
        )
    }

    private fun createFixture(): ReaderBenchmarkFixture {
        val heights = intArrayOf(540, 720, 840, 960, 1080, 1200, 780, 1020, 660, 1140, 900, 1260)
        val bitmaps = heights.mapIndexed { index, height -> createPatternBitmap(index, height) }
        val pages = List(PAGE_COUNT) { index ->
            BenchmarkPage(
                id = PageId(index.toLong()),
                assetIndex = index % bitmaps.size,
            )
        }
        val displayMetrics = resources.displayMetrics
        val scene = VerticalReaderScene(
            availableWidth = displayMetrics.widthPixels,
            defaultViewportHeight = displayMetrics.heightPixels,
            initialPages = pages.map { page ->
                val bitmap = bitmaps[page.assetIndex]
                page.id to PageGeometryHint.Exact(bitmap.width, bitmap.height)
            },
        )
        return ReaderBenchmarkFixture(
            bitmaps = bitmaps,
            pages = pages,
            pagesById = pages.associateBy(BenchmarkPage::id),
            scene = scene,
        )
    }

    private fun createPatternBitmap(index: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(FIXTURE_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = AndroidColor.rgb(
            35 + index * 13 % 170,
            45 + index * 29 % 160,
            55 + index * 47 % 150,
        )
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
        repeat(18) { stripe ->
            paint.color = AndroidColor.argb(
                90,
                255 - index * 11 % 180,
                220 - stripe * 7 % 150,
                180 + index * 5 % 70,
            )
            val top = stripe * bitmap.height / 18f
            canvas.drawRect(0f, top, bitmap.width.toFloat(), top + 8f, paint)
        }
        return bitmap
    }

    companion object {
        const val EXTRA_BACKEND = "backend"
        const val BACKEND_LAZY = "lazy"
        const val BACKEND_COMPOSE_SCENE = "compose_scene"
        const val BACKEND_VIEW_SCENE = "view_scene"
        const val BENCHMARK_SURFACE_TAG = "reader_benchmark_surface"

        private const val PAGE_COUNT = 100
        private const val FIXTURE_WIDTH = 480
    }
}

private data class ReaderBenchmarkFixture(
    val bitmaps: List<Bitmap>,
    val pages: List<BenchmarkPage>,
    val pagesById: Map<PageId, BenchmarkPage>,
    val scene: VerticalReaderScene,
)

private data class BenchmarkPage(
    val id: PageId,
    val assetIndex: Int,
)

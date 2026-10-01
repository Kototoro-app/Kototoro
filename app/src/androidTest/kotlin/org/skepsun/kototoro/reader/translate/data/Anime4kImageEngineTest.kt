package org.skepsun.kototoro.reader.translate.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.reader.domain.ReaderSuperResolutionManager
import org.skepsun.kototoro.video.player.Anime4KShaderAssets

/** Exercises bundled shaders and the production cache with isolated files, without contacting a source. */
@RunWith(AndroidJUnit4::class)
class Anime4kImageEngineTest {

    @Test
    fun bundledPresetsProduceImagesWithPreservedColorRegions() = runBlocking {
        val context = isolatedContext()
        val shaders = Anime4KShaderAssets.ensureShadersCopied(context)
        val input = image()
        val presets = listOf(
            "A" to Anime4KShaderAssets.modeAPreset,
            "B" to Anime4KShaderAssets.modeBPreset,
            "C" to Anime4KShaderAssets.modeCPreset,
            "AA" to Anime4KShaderAssets.modeAPlusPreset,
            "BB" to Anime4KShaderAssets.modeBPlusPreset,
            "CA" to Anime4KShaderAssets.modeCAPlusPreset,
        )
        try {
            for ((name, preset) in presets) {
                val engine = Anime4kImageEngine(context)
                try {
                    assertTrue("$name must initialize the real GL shaders", engine.initialize(shaders, preset))
                    val output = requireNotNull(engine.process(input)) { "$name returned no image" }
                    try {
                        assertTrue("$name must preserve image dimensions", output.width >= input.width)
                        assertTrue("$name must preserve image dimensions", output.height >= input.height)
                        if (name == "C" || name == "CA") {
                            assertEquals(input.width, output.width)
                            assertEquals(input.height, output.height)
                        }
                        assertColorRegions(output, name)
                    } finally {
                        output.recycle()
                    }
                } finally {
                    engine.release()
                }
            }
        } finally {
            input.recycle()
        }
    }

    @Test
    fun differentPagesWithTheSameFilenameDoNotShareAnEnhancedImage() = runBlocking {
        val context = isolatedContext()
        val manager = ReaderSuperResolutionManager(
            context, OnnxModelManager(context, OkHttpClient(), AppSettings(context)),
        )
        val first = File(context.filesDir, "chapter-one/page.png")
        val second = File(context.filesDir, "chapter-two/page.png")
        writeImage(first, reversed = false)
        writeImage(second, reversed = true)
        try {
            val firstResult = requireNotNull(manager.processImage(first.toUri(), "ANIME4K_C", 0, -1))
            val secondResult = requireNotNull(manager.processImage(second.toUri(), "ANIME4K_C", 0, -1))
            assertNotEquals("Different pages must not resolve to the same cache entry", firstResult, secondResult)
            val output = requireNotNull(BitmapFactory.decodeFile(secondResult.path))
            try {
                assertColorRegions(output, "Second chapter", reversed = true)
            } finally {
                output.recycle()
            }
        } finally {
            manager.release()
        }
    }

    @Test
    fun updatedPageDoesNotReuseAnOutdatedEnhancedImage() = runBlocking {
        val context = isolatedContext()
        val manager = ReaderSuperResolutionManager(
            context, OnnxModelManager(context, OkHttpClient(), AppSettings(context)),
        )
        val page = File(context.filesDir, "chapter/page.png")
        writeImage(page, reversed = false)
        try {
            val firstResult = requireNotNull(manager.processImage(page.toUri(), "ANIME4K_C", 0, -1))
            assertEquals(
                "An unchanged page must reuse its cache",
                firstResult,
                manager.processImage(page.toUri(), "ANIME4K_C", 0, -1),
            )
            val previousModified = page.lastModified()
            writeImage(page, reversed = true)
            assertTrue(page.setLastModified(previousModified + 2_000))
            val secondResult = requireNotNull(manager.processImage(page.toUri(), "ANIME4K_C", 0, -1))
            assertNotEquals("An updated page must not reuse its previous result", firstResult, secondResult)
            val output = requireNotNull(BitmapFactory.decodeFile(secondResult.path))
            try {
                assertColorRegions(output, "Updated page", reversed = true)
            } finally {
                output.recycle()
            }
        } finally {
            manager.release()
        }
    }

    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "anime4k-test-${System.nanoTime()}"
        val root = File(base.cacheDir, namespace).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    private fun image(reversed: Boolean = false): Bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        .apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val red = (x < width / 2) != reversed
                    setPixel(x, y, if (red) Color.rgb(200, 40, 20) else Color.rgb(20, 40, 200))
                }
            }
        }

    private fun writeImage(file: File, reversed: Boolean) {
        file.parentFile!!.mkdirs()
        val bitmap = image(reversed)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorRegions(bitmap: Bitmap, label: String, reversed: Boolean = false) {
        val left = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
        val right = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
        val red = if (reversed) right else left
        val blue = if (reversed) left else right
        assertTrue("$label red region must stay red", Color.red(red) > Color.blue(red) + 50)
        assertTrue("$label blue region must stay blue", Color.blue(blue) > Color.red(blue) + 50)
    }
}

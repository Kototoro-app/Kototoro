package org.skepsun.kototoro.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.GlobalScope
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.RealCuganNcnnEngine
import org.skepsun.kototoro.reader.translate.data.RealEsrganNcnnEngine
import org.skepsun.kototoro.reader.translate.data.Anime4kImageEngine
import org.skepsun.kototoro.video.player.Anime4KShaderAssets
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderSuperResolutionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val onnxModelManager: OnnxModelManager,
) {
    private val TAG = "ReaderSuperResolutionManager"
    private val engineMutex = Mutex()
    private val cacheDir = File(context.cacheDir, "sr_cache").apply { mkdirs() }

    private var activeModelId: String? = null
    private var realesrganEngine: RealEsrganNcnnEngine? = null
    private var realcuganEngine: RealCuganNcnnEngine? = null
    private var anime4kEngine: Anime4kImageEngine? = null

    // GPU device-lost recovery: track consecutive failures to detect irrecoverable state.
    // Once VK_ERROR_DEVICE_LOST fires, the Vulkan device is PERMANENTLY broken for
    // this process lifetime. Every subsequent vkQueueSubmit will fail, flooding logcat
    // with thousands of errors and wasting battery. After MAX_CONSECUTIVE_FAILURES
    // we stop trying until the user restarts the app.
    private var consecutiveFailures = 0
    private val MAX_CONSECUTIVE_FAILURES = 3

    // Maximum input pixels to prevent enormous output bitmaps that OOM or overwhelm the GPU.
    // ESRGAN 4x: 1500x2100 input -> 6000x8400 output = 50M px = 200MB bitmap
    // CUGaN  2x: 3000x4200 input -> 6000x8400 output = 50M px = 200MB bitmap
    private val MAX_INPUT_PIXELS_ESRGAN = 1500L * 2100L  // ~3.15M px -> 50M px output
    private val MAX_INPUT_PIXELS_CUGAN  = 3000L * 4200L  // ~12.6M px -> 50M px output

    suspend fun processImage(
        originalUri: Uri,
        modelId: String,
        noiseLevel: Int,
        cacheLimitMb: Int
    ): Uri? = withContext(Dispatchers.IO) {
        if (originalUri.scheme != "file") {
            return@withContext null
        }
        val originalFile = originalUri.toFile()
        if (!originalFile.exists()) return@withContext null

        val hash = "${originalFile.name}_${modelId}_sr".hashCode().toString()
        val outputFile = File(cacheDir, "sr_$hash.webp")

        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "Using cached SR image: ${outputFile.name}")
            updateCacheLru(outputFile)
            return@withContext outputFile.toUri()
        }

        // If we've hit too many consecutive GPU failures, the Vulkan device is likely
        // in an irrecoverable DEVICE_LOST state. Skip to avoid flooding logcat.
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "SR engine disabled: $consecutiveFailures consecutive GPU failures. " +
                    "Restart app to retry.")
            return@withContext null
        }

        Log.d(TAG, "Starting SR processing for ${originalFile.name} with model $modelId")

        val resultBitmap: Bitmap? = try {
            engineMutex.withLock {
                if (!isActive) return@withLock null
                initEngineIfNeeded(modelId)

                val isEsrgan = modelId.contains("realesrgan", ignoreCase = true)
                val isAnime4k = modelId.startsWith("ANIME4K_")

                // Decode only dimensions first to check size limits
                val boundsOpts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(originalFile.absolutePath, boundsOpts)

                val inputPixels = boundsOpts.outWidth.toLong() * boundsOpts.outHeight.toLong()

                if (!isAnime4k) {
                    val maxPixels = if (isEsrgan) MAX_INPUT_PIXELS_ESRGAN else MAX_INPUT_PIXELS_CUGAN

                    if (inputPixels > maxPixels) {
                        Log.d(TAG, "Skipping SR: input ${boundsOpts.outWidth}x${boundsOpts.outHeight}" +
                                " (${inputPixels / 1_000_000}M px) exceeds limit for ${if (isEsrgan) "4x" else "2x"}")
                        return@withLock null
                    }
                }

                // Force ARGB_8888 — BitmapFactory might pick RGB_565 for JPEGs
                // on some devices, which breaks the native 4-byte-per-pixel assumption.
                val decodeOpts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val originalBitmap = BitmapFactory.decodeFile(originalFile.absolutePath, decodeOpts)
                    ?: return@withLock null

                val outBmp: Bitmap? = if (isAnime4k) {
                    anime4kEngine?.process(originalBitmap)
                } else if (isEsrgan) {
                    realesrganEngine?.process(originalBitmap)
                } else {
                    realcuganEngine?.process(originalBitmap)
                }
                originalBitmap.recycle()

                // Validate: null output likely means GPU DEVICE_LOST
                if (outBmp == null) {
                    Log.e(TAG, "SR process returned null — possible GPU device lost")
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.e(TAG, "GPU appears irrecoverable. Destroying engine.")
                        releaseEngines()
                    }
                    null
                } else {
                    consecutiveFailures = 0  // reset on success
                    outBmp
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NCNN SR processing failed", e)
            consecutiveFailures++
            null
        }

        if (resultBitmap != null) {
            try {
                FileOutputStream(outputFile).use { out ->
                    resultBitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
                manageCache(cacheLimitMb)
                return@withContext outputFile.toUri()
            } catch (e: Exception) {
                Log.e(TAG, "NCNN SR Processing Save failed", e)
                outputFile.delete()
            } finally {
                resultBitmap.recycle()
            }
        }

        null
    }

    private suspend fun initEngineIfNeeded(modelId: String) {
        if (activeModelId == modelId) {
            if (realesrganEngine != null || realcuganEngine != null || anime4kEngine != null) return
        }

        releaseEngines()
        consecutiveFailures = 0  // reset counter when switching engines

        if (modelId.startsWith("ANIME4K_")) {
            val preset = when (modelId) {
                "ANIME4K_A" -> Anime4KShaderAssets.modeAPreset
                "ANIME4K_B" -> Anime4KShaderAssets.modeBPreset
                "ANIME4K_C" -> Anime4KShaderAssets.modeCPreset
                "ANIME4K_AA" -> Anime4KShaderAssets.modeAPlusPreset
                "ANIME4K_BB" -> Anime4KShaderAssets.modeBPlusPreset
                "ANIME4K_CA" -> Anime4KShaderAssets.modeCAPlusPreset
                else -> Anime4KShaderAssets.modeAPreset
            }
            val shadersDir = Anime4KShaderAssets.ensureShadersCopied(context)
            anime4kEngine = Anime4kImageEngine(context)
            anime4kEngine?.initialize(shadersDir, preset)
        } else {
            if (!onnxModelManager.isModelDownloaded(modelId)) {
                Log.e(TAG, "SR model not downloaded: $modelId")
                throw IllegalStateException("Super resolution model not downloaded")
            }

            val modelsDir = onnxModelManager.getModelDir(modelId)
            val expectedParamName = if (modelId.contains("realesrgan", ignoreCase = true))
                "realesrgan-x4plus-anime.param" else "up2x-conservative.param"
            val expectedBinName = if (modelId.contains("realesrgan", ignoreCase = true))
                "realesrgan-x4plus-anime.bin" else "up2x-conservative.bin"

            val paramFile = modelsDir.walkTopDown().firstOrNull { it.name == expectedParamName }
                ?: throw IllegalStateException("No parameter file $expectedParamName found for model $modelId")
            val binFile = modelsDir.walkTopDown().firstOrNull { it.name == expectedBinName }
                ?: throw IllegalStateException("No binary file $expectedBinName found for model $modelId")

            if (modelId.contains("realesrgan", ignoreCase = true)) {
                realesrganEngine = RealEsrganNcnnEngine()
                realesrganEngine?.initialize(paramFile.absolutePath, binFile.absolutePath, ttaMode = false)
            } else {
                realcuganEngine = RealCuganNcnnEngine()
                realcuganEngine?.initialize(paramFile.absolutePath, binFile.absolutePath, ttaMode = false)
            }
        }
        activeModelId = modelId
    }

    private fun releaseEngines() {
        realesrganEngine?.release()
        realesrganEngine = null
        realcuganEngine?.release()
        realcuganEngine = null
        anime4kEngine?.release()
        anime4kEngine = null
    }

    private fun updateCacheLru(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun manageCache(limitMb: Int) {
        if (limitMb < 0) return
        val limitBytes = limitMb * 1024L * 1024L
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }

        for (file in files) {
            if (totalSize <= limitBytes) break
            totalSize -= file.length()
            file.delete()
        }
    }

    fun release() {
        GlobalScope.launch(Dispatchers.IO) {
            engineMutex.withLock {
                releaseEngines()
                activeModelId = null
                consecutiveFailures = 0
            }
        }
    }
}

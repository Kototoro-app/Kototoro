package org.skepsun.kototoro.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.GlobalScope
import okio.ByteString.Companion.encodeUtf8
import org.skepsun.kototoro.core.util.ContentZipArchive
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.toUnderlyingZipUri
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.RealCuganNcnnEngine
import org.skepsun.kototoro.reader.translate.data.RealEsrganNcnnEngine
import org.skepsun.kototoro.reader.translate.data.Anime4kImageEngine
import org.skepsun.kototoro.video.player.Anime4KShaderAssets
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
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

    private val OUTPUT_EXTENSIONS = listOf("jpg", "webp")
    private val OUTPUT_QUALITY = 92

    // The native library stays loaded once an NCNN engine has been created, so pausing is safe from then on.
    @Volatile
    private var isNcnnLibraryLoaded = false
    private var lastTouchPauseAt = 0L
    private val TOUCH_PAUSE_MS = 1_000L
    private val TOUCH_PAUSE_RENEW_MS = 100L
    private val FLING_PAUSE_MS = 800L

    suspend fun processImage(
        originalUri: Uri,
        modelId: String,
        noiseLevel: Int,
        cacheLimitMb: Int,
        skipFromWidth: Int = 0,
    ): Uri? = withContext(Dispatchers.IO) {
        val source = resolveSource(originalUri) ?: return@withContext null

        // Filenames repeat across chapters; a replaced source file must also invalidate its result.
        val hash = (source.identity + listOf(modelId, noiseLevel.toString()))
            .joinToString("\u0000").encodeUtf8().sha256().hex()
        val cachedFile = OUTPUT_EXTENSIONS.firstNotNullOfOrNull { extension ->
            File(cacheDir, "sr_$hash.$extension").takeIf { it.exists() && it.length() > 0 }
        }
        if (cachedFile != null) {
            Log.d(TAG, "Using cached SR image: ${cachedFile.name}")
            updateCacheLru(cachedFile)
            return@withContext cachedFile.toUri()
        }

        // Decode only dimensions first to check size limits
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        source.decode(boundsOpts)
        // A page already as wide as the screen is shown downscaled, so upscaling it in real time would only
        // cost GPU time while the reader scrolls. Prepared results above are still used for such pages.
        if (skipFromWidth > 0 && boundsOpts.outWidth >= skipFromWidth) {
            Log.d(TAG, "Skipping SR: input width ${boundsOpts.outWidth} already covers $skipFromWidth px")
            return@withContext null
        }

        // If we've hit too many consecutive GPU failures, the Vulkan device is likely
        // in an irrecoverable DEVICE_LOST state. Skip to avoid flooding logcat.
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "SR engine disabled: $consecutiveFailures consecutive GPU failures. " +
                    "Restart app to retry.")
            return@withContext null
        }

        Log.d(TAG, "Starting SR processing for $originalUri with model $modelId")
        val startedAt = SystemClock.elapsedRealtime()
        var decodedAt = startedAt
        var processedAt = startedAt

        var isOpaqueSource = false
        val resultBitmap: Bitmap? = try {
            engineMutex.withLock {
                if (!isActive) return@withLock null
                initEngineIfNeeded(modelId)

                val isEsrgan = modelId.contains("realesrgan", ignoreCase = true)
                val ncnnModel = NcnnModel.of(modelId)
                val isAnime4k = modelId.startsWith("ANIME4K_")

                val inputPixels = boundsOpts.outWidth.toLong() * boundsOpts.outHeight.toLong()

                if (!isAnime4k) {
                    val maxPixels = if (ncnnModel.scale >= 4) MAX_INPUT_PIXELS_ESRGAN else MAX_INPUT_PIXELS_CUGAN

                    if (inputPixels > maxPixels) {
                        Log.d(TAG, "Skipping SR: input ${boundsOpts.outWidth}x${boundsOpts.outHeight}" +
                                " (${inputPixels / 1_000_000}M px) exceeds limit for ${ncnnModel.scale}x")
                        return@withLock null
                    }
                }

                // Force ARGB_8888 — BitmapFactory might pick RGB_565 for JPEGs
                // on some devices, which breaks the native 4-byte-per-pixel assumption.
                val decodeOpts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val originalBitmap = source.decode(decodeOpts) ?: return@withLock null
                decodedAt = SystemClock.elapsedRealtime()

                val outBmp: Bitmap? = if (isAnime4k) {
                    anime4kEngine?.process(originalBitmap)
                } else if (isEsrgan) {
                    realesrganEngine?.process(originalBitmap)
                } else {
                    realcuganEngine?.process(originalBitmap)
                }
                isOpaqueSource = !originalBitmap.hasAlpha()
                processedAt = SystemClock.elapsedRealtime()
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
            // Turning pages cancels the loads of pages left behind; that is not a GPU failure, and counting it
            // switched super resolution off for the rest of the process after three quick page turns.
            if (e is CancellationException) throw e
            Log.e(TAG, "NCNN SR processing failed", e)
            consecutiveFailures++
            null
        }

        if (resultBitmap != null) {
            // Encoding a 2x page as WebP took longer than many upscales; JPEG is several times faster to write
            // and to decode. Sources with transparency keep WebP, which JPEG would fill with black.
            val (format, extension) = if (isOpaqueSource) {
                Bitmap.CompressFormat.JPEG to "jpg"
            } else {
                Bitmap.CompressFormat.WEBP to "webp"
            }
            val outputFile = File(cacheDir, "sr_$hash.$extension")
            val partFile = File(cacheDir, "sr_$hash.$extension.part")
            try {
                FileOutputStream(partFile).use { out ->
                    resultBitmap.compress(format, OUTPUT_QUALITY, out)
                }
                check(partFile.renameTo(outputFile)) { "Cannot publish ${outputFile.name}" }
                manageCache(cacheLimitMb)
                Log.d(
                    TAG,
                    "SR done ${boundsOpts.outWidth}x${boundsOpts.outHeight}: wait+decode=${decodedAt - startedAt}ms " +
                        "process=${processedAt - decodedAt}ms save=${SystemClock.elapsedRealtime() - processedAt}ms",
                )
                return@withContext outputFile.toUri()
            } catch (e: Exception) {
                Log.e(TAG, "NCNN SR Processing Save failed", e)
                partFile.delete()
                outputFile.delete()
            } finally {
                resultBitmap.recycle()
            }
        }

        null
    }

    /**
     * Downloaded chapters are CBZ archives, so pages reach the reader as `file+zip`/`content+zip` entries.
     * Their identity is the archive and entry rather than a decoded copy in the page cache, whose name
     * changes every time it is rewritten; this keeps prepared results reachable from the reader.
     */
    private fun resolveSource(uri: Uri): SourceImage? = when {
        uri.isFileUri() -> {
            val file = uri.toFile()
            if (!file.exists()) {
                null
            } else {
                SourceImage(
                    identity = listOf(file.canonicalPath, file.length().toString(), file.lastModified().toString()),
                ) { options -> BitmapFactory.decodeFile(file.absolutePath, options) }
            }
        }

        uri.isZipUri() -> {
            val entryName = uri.fragment
            val archive = File(uri.path ?: uri.schemeSpecificPart)
            if (entryName.isNullOrEmpty() || !archive.isFile) {
                null
            } else {
                // An unreadable archive is a missing page, not a GPU failure.
                val bytes by lazy {
                    runCatching {
                        ZipFile(archive).use { zip ->
                            zip.getEntry(entryName)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                        }
                    }.getOrNull()
                }
                SourceImage(
                    identity = listOf(
                        archive.canonicalPath,
                        archive.length().toString(),
                        archive.lastModified().toString(),
                        entryName,
                    ),
                ) { options -> bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, options) } }
            }
        }

        uri.isContentZipUri() -> {
            val entryName = uri.fragment
            val archiveUri = uri.toUnderlyingZipUri()
            val archive = DocumentFile.fromSingleUri(context, archiveUri)
            if (entryName.isNullOrEmpty() || archive == null || !archive.exists()) {
                null
            } else {
                val bytes by lazy {
                    runCatching {
                        ContentZipArchive.withEntry(context.contentResolver, uri) { it.readBytes() }
                    }.getOrNull()
                }
                SourceImage(
                    identity = listOf(
                        archiveUri.toString(),
                        archive.length().toString(),
                        archive.lastModified().toString(),
                        entryName,
                    ),
                ) { options -> bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, options) } }
            }
        }

        else -> null
    }

    private class SourceImage(
        val identity: List<String>,
        val decode: (BitmapFactory.Options) -> Bitmap?,
    )

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
            val ncnnModel = NcnnModel.of(modelId)
            val expectedParamName = "${ncnnModel.fileName}.param"
            val expectedBinName = "${ncnnModel.fileName}.bin"

            val paramFile = modelsDir.walkTopDown().firstOrNull { it.name == expectedParamName }
                ?: throw IllegalStateException("No parameter file $expectedParamName found for model $modelId")
            val binFile = modelsDir.walkTopDown().firstOrNull { it.name == expectedBinName }
                ?: throw IllegalStateException("No binary file $expectedBinName found for model $modelId")

            if (modelId.contains("realesrgan", ignoreCase = true)) {
                realesrganEngine = RealEsrganNcnnEngine()
                realesrganEngine?.initialize(
                    paramFile.absolutePath,
                    binFile.absolutePath,
                    ttaMode = false,
                    scale = ncnnModel.scale,
                )
            } else {
                realcuganEngine = RealCuganNcnnEngine()
                realcuganEngine?.initialize(paramFile.absolutePath, binFile.absolutePath, ttaMode = false)
            }
            isNcnnLibraryLoaded = true
        }
        activeModelId = modelId
    }

    /**
     * Gives the reader's frames the GPU while the user touches or flings: NCNN holds between tiles until the
     * gesture settles (see sr_yield.h). Anime4K runs short GL passes and needs no pause.
     */
    fun onReaderTouch(action: Int) {
        if (!isNcnnLibraryLoaded) return
        val now = SystemClock.uptimeMillis()
        val pauseMs = when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> FLING_PAUSE_MS
            MotionEvent.ACTION_MOVE -> if (now - lastTouchPauseAt < TOUCH_PAUSE_RENEW_MS) return else TOUCH_PAUSE_MS
            else -> TOUCH_PAUSE_MS
        }
        lastTouchPauseAt = now
        RealCuganNcnnEngine.pauseForNative(pauseMs)
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

    /** The model files inside an NCNN model package and the scale they upscale by. */
    private enum class NcnnModel(val fileName: String, val scale: Int) {
        REALESRGAN_X4PLUS_ANIME("realesrgan-x4plus-anime", 4),
        REALESR_ANIMEVIDEOV3_X2("realesr-animevideov3-x2", 2),
        REALCUGAN_2X_CONSERVATIVE("up2x-conservative", 2),
        ;

        companion object {
            fun of(modelId: String): NcnnModel = when {
                modelId.contains("animevideov3", ignoreCase = true) -> REALESR_ANIMEVIDEOV3_X2
                modelId.contains("realesrgan", ignoreCase = true) -> REALESRGAN_X4PLUS_ANIME
                else -> REALCUGAN_2X_CONSERVATIVE
            }
        }
    }
}

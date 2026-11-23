package org.skepsun.kototoro.reader.translate.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

class RealCuganNcnnEngine(private val gpuId: Int = 0) {
    private var nativeHandle: Long = 0

    init {
        System.loadLibrary("realesrgan_ncnn") // using the shared lib containing both
    }

    private external fun initNative(paramPath: String, binPath: String, gpuId: Int, ttaMode: Boolean): Long
    private external fun processNative(handle: Long, inBitmap: Bitmap, outBitmap: Bitmap): Boolean
    private external fun releaseNative(handle: Long)

    suspend fun initialize(paramPath: String, binPath: String, ttaMode: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        nativeHandle = initNative(paramPath, binPath, gpuId, ttaMode)
        nativeHandle != 0L
    }

    suspend fun process(inBitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (nativeHandle == 0L) {
            Log.e("RealCuganNcnnEngine", "Engine not initialized.")
            return@withContext null
        }

        val outW = inBitmap.width * 2
        val outH = inBitmap.height * 2

        val outBitmap = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e("RealCuganNcnnEngine", "OOM allocating ${outW}x${outH} output bitmap " +
                    "(${outW.toLong() * outH * 4 / 1_048_576}MB)")
            return@withContext null
        }

        val oldPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val success = processNative(nativeHandle, inBitmap, outBitmap)
            if (success) {
                outBitmap
            } else {
                outBitmap.recycle()
                null
            }
        } finally {
            android.os.Process.setThreadPriority(oldPriority)
        }
    }

    fun release() {
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
    }

    companion object {

        /**
         * Holds RealCUGAN and RealESRGAN (they share the native library) between tiles for [durationMs], so the
         * reader's frames get the GPU while it is being touched or flung. Later calls extend the pause.
         */
        @JvmStatic
        external fun pauseForNative(durationMs: Long)
    }
}

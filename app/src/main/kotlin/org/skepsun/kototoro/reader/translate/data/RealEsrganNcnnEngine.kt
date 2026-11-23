package org.skepsun.kototoro.reader.translate.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

class RealEsrganNcnnEngine(private val gpuId: Int = 0) {
    private var nativeHandle: Long = 0
    private var scale = 4

    init {
        System.loadLibrary("realesrgan_ncnn") // using the shared lib containing both
    }

    private external fun initNative(paramPath: String, binPath: String, gpuId: Int, ttaMode: Boolean, scale: Int): Long
    private external fun processNative(handle: Long, inBitmap: Bitmap, outBitmap: Bitmap): Boolean
    private external fun releaseNative(handle: Long)

    suspend fun initialize(
        paramPath: String,
        binPath: String,
        ttaMode: Boolean = false,
        scale: Int = 4,
    ): Boolean = withContext(Dispatchers.IO) {
        this@RealEsrganNcnnEngine.scale = scale
        nativeHandle = initNative(paramPath, binPath, gpuId, ttaMode, scale)
        nativeHandle != 0L
    }

    suspend fun process(inBitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (nativeHandle == 0L) {
            Log.e("RealEsrganNcnnEngine", "Engine not initialized.")
            return@withContext null
        }

        val outW = inBitmap.width * scale
        val outH = inBitmap.height * scale

        val outBitmap = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e("RealEsrganNcnnEngine", "OOM allocating ${outW}x${outH} output bitmap " +
                    "(${outW.toLong() * outH * 4 / 1_048_576}MB)")
            return@withContext null
        }

        // Lower thread priority so NCNN processing doesn't compete with UI for CPU time.
        // The GPU-side yields (sleep_for in C++) handle GPU contention; this handles CPU.
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
}

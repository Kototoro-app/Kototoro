package org.skepsun.kototoro.core.image

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aomedia.avif.android.AvifDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in native benchmark; pass avifPerformanceFile as an instrumentation argument. */
@RunWith(AndroidJUnit4::class)
class AvifPlaybackBenchmark {

    @Test
    fun compareNativePlaybackPaths() = withForegroundFile(::benchmark)

    @Test
    fun compareBudgetedFrameDelivery() = withForegroundFile(::benchmarkDelivery)

    private fun withForegroundFile(block: (String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("avifPerformanceFile")
        assumeTrue("Pass avifPerformanceFile to benchmark a local animation", path != null)
        val scenario = ActivityScenario.launch<IdleProbeActivity>(
            Intent(instrumentation.targetContext, IdleProbeActivity::class.java)
                .putExtra("scene_recovery", true),
        )
        try {
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            block(requireNotNull(path))
        } finally {
            scenario.close()
        }
    }

    private fun benchmarkDelivery(path: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = FileInputStream(File(path)).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
        }
        for (bufferCount in listOf(2, 8)) {
            val decoder = requireNotNull(AvifDecoder.create(input, 2))
            val buffers = List(bufferCount) {
                Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
            }
            assertEquals(0, decoder.nthFrame(0, buffers.first()))
            val durations = LongArray(decoder.frameCount) { (decoder.frameDurations[it] * 1000).toLong() }
            val drawable = AvifAnimatedDrawable(AvifFrameStream(decoder, input, buffers), durations, -1)
            val timestamps = ArrayList<Long>()
            val delivered = CountDownLatch(96)
            val callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    timestamps.add(SystemClock.uptimeMillis())
                    delivered.countDown()
                }

                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            }
            try {
                instrumentation.runOnMainSync {
                    drawable.callback = callback
                    drawable.start()
                }
                assertTrue("Frame delivery timed out", delivered.await(15, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    drawable.stop()
                    val intervals = timestamps.take(96).drop(10).zipWithNext { a, b -> b - a }
                    val sorted = intervals.sorted()
                    report("delivery buffers=$bufferCount rgb_mib=${drawable.byteCount / 1048576.0} " +
                        "avg_ms=${intervals.average()} p95_ms=${sorted[(sorted.size * .95).toInt()]} " +
                        "max_ms=${sorted.last()} fps=${1000 / intervals.average()} " +
                        "late=${intervals.count { it > durations.first() * 1.5 }}/${intervals.size}")
                }
            } finally {
                instrumentation.runOnMainSync { drawable.release() }
                keepAvifInputReachable(callback)
                keepAvifInputReachable(input)
            }
        }
    }

    private fun benchmark(path: String) {
        val input = FileInputStream(File(path)).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
        }
        val metadata = requireNotNull(AvifDecoder.create(input, 2))
        val width = metadata.width
        val height = metadata.height
        val count = metadata.frameCount
        val durations = metadata.frameDurations
        report("AVIF ${width}x$height, $count frames, durations_ms=" +
            durations.map { it * 1000 }.distinct().joinToString() + ", ${AvifDecoder.versionString()}")
        metadata.release()
        try {
            assumeTrue("Benchmark requires at least five animation frames", count > 4)
            val settings = listOf(
                Triple(2, false, 1), Triple(2, true, 1), Triple(4, true, 1),
                Triple(8, true, 1), Triple(2, true, 4),
            )
            for ((threads, sequential, divisor) in settings) {
                val decoder = requireNotNull(AvifDecoder.create(input, threads))
                val buffers = List(2) {
                    Bitmap.createBitmap(width / divisor, height / divisor, Bitmap.Config.ARGB_8888)
                }
                val timings = ArrayList<Double>()
                var late = 0
                try {
                    for (index in 0 until minOf(count, 48)) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val result = if (sequential) decoder.nextFrame(buffers[index % 2])
                        else decoder.nthFrame(index, buffers[index % 2])
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        assertEquals(0, result)
                        if (index >= 4) {
                            timings.add(elapsed)
                            if (elapsed > durations[index] * 1000) late++
                        }
                    }
                    val sorted = timings.sorted()
                    report("threads=$threads sequential=$sequential divisor=$divisor " +
                        "avg_ms=${timings.average()} p50_ms=${sorted[sorted.size / 2]} " +
                        "p95_ms=${sorted[(sorted.size * .95).toInt()]} max_ms=${sorted.last()} " +
                        "late=$late/${timings.size}")
                } finally {
                    decoder.release()
                    buffers.forEach { it.recycle() }
                }
            }
        } finally {
            keepAvifInputReachable(input)
        }
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "AVIF_BENCH: $message\n")
        })
    }
}

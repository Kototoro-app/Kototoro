package org.skepsun.kototoro.macrobenchmark

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderProductionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    // --- 1. Burst Suite (Primary: Partial, Diagnostic: Full) ---

    @Test
    fun burstLegacyPartial() = measureBurst(
        backend = BACKEND_LEGACY_WEBTOON,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    @Test
    fun burstScenePartial() = measureBurst(
        backend = BACKEND_SCENE_WEBTOON,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    @Test
    fun burstLegacyFull() = measureBurst(
        backend = BACKEND_LEGACY_WEBTOON,
        compilationMode = CompilationMode.Full(),
    )

    @Test
    fun burstSceneFull() = measureBurst(
        backend = BACKEND_SCENE_WEBTOON,
        compilationMode = CompilationMode.Full(),
    )

    // --- Phase 1D: Ultra-Long Webtoon Suite (10,000px ~ 40,000px) ---

    @Test
    fun ultraLongSceneFull() = measureBurst(
        backend = BACKEND_SCENE_WEBTOON,
        compilationMode = CompilationMode.Full(),
        fixtureMode = FIXTURE_MODE_ULTRA_LONG,
    )

    @Test
    fun ultraLongLegacyFull() = measureBurst(
        backend = BACKEND_LEGACY_WEBTOON,
        compilationMode = CompilationMode.Full(),
        fixtureMode = FIXTURE_MODE_ULTRA_LONG,
    )

    // --- 2. Sustained Suite (Primary: Partial, Diagnostic: Full) ---

    @Test
    fun sustainedLegacyPartial() = measureSustained(
        backend = BACKEND_LEGACY_WEBTOON,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    @Test
    fun sustainedScenePartial() = measureSustained(
        backend = BACKEND_SCENE_WEBTOON,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    @Test
    fun sustainedLegacyFull() = measureSustained(
        backend = BACKEND_LEGACY_WEBTOON,
        compilationMode = CompilationMode.Full(),
    )

    @Test
    fun sustainedSceneFull() = measureSustained(
        backend = BACKEND_SCENE_WEBTOON,
        compilationMode = CompilationMode.Full(),
    )

    // --- 3. Optional Energy Suite ---

    @Test
    fun energyLegacy() = measureEnergy(BACKEND_LEGACY_WEBTOON)

    @Test
    fun energyScene() = measureEnergy(BACKEND_SCENE_WEBTOON)

    private fun measureBurst(
        backend: String,
        compilationMode: CompilationMode,
        fixtureMode: String = FIXTURE_MODE_STANDARD,
    ) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                FrameTimingMetric(),
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Max,
                    subMetrics = listOf(
                        MemoryUsageMetric.SubMetric.HeapSize,
                        MemoryUsageMetric.SubMetric.RssAnon,
                        MemoryUsageMetric.SubMetric.RssShmem,
                        MemoryUsageMetric.SubMetric.Gpu,
                    ),
                    metricNameSuffix = "Max",
                ),
                ActivePresentationAssetsMetric(),
            ),
            compilationMode = compilationMode,
            startupMode = null,
            iterations = 5,
            setupBlock = { setupIteration(backend, fixtureMode) },
        ) {
            burstFling()
        }
    }

    private fun measureSustained(backend: String, compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                FrameTimingMetric(),
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Max,
                    subMetrics = listOf(
                        MemoryUsageMetric.SubMetric.HeapSize,
                        MemoryUsageMetric.SubMetric.RssAnon,
                        MemoryUsageMetric.SubMetric.RssShmem,
                        MemoryUsageMetric.SubMetric.Gpu,
                    ),
                    metricNameSuffix = "Max",
                ),
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Last,
                    subMetrics = listOf(
                        MemoryUsageMetric.SubMetric.HeapSize,
                        MemoryUsageMetric.SubMetric.RssAnon,
                        MemoryUsageMetric.SubMetric.RssShmem,
                        MemoryUsageMetric.SubMetric.Gpu,
                    ),
                    metricNameSuffix = "Last",
                ),
                ActivePresentationAssetsMetric(),
            ),
            compilationMode = compilationMode,
            startupMode = null,
            iterations = 2,
            setupBlock = { setupIteration(backend) },
        ) {
            sustainedTraversal(cycles = 2)
        }
    }

    private fun measureEnergy(backend: String) {
        val energySupported = PowerMetric.deviceSupportsHighPrecisionTracking() &&
            PowerMetric.deviceBatteryHasMinimumCharge()
        val metrics: List<Metric> = if (energySupported) {
            listOf(PowerMetric(PowerMetric.Energy()))
        } else {
            listOf(FrameTimingMetric())
        }

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = metrics,
            compilationMode = CompilationMode.Full(),
            startupMode = null,
            iterations = 2,
            setupBlock = { setupIteration(backend) },
        ) {
            sustainedTraversal(cycles = 1)
        }
    }

    private fun MacrobenchmarkScope.setupIteration(
        backend: String,
        fixtureMode: String = FIXTURE_MODE_STANDARD,
    ) {
        killProcess()
        pressHome()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                android.util.Log.e("ReaderBenchmark", "Received ACTION_BENCHMARK_READY broadcast for backend=$backend, fixtureMode=$fixtureMode")
                latch.countDown()
            }
        }
        val filter = IntentFilter(ACTION_BENCHMARK_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            targetContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            targetContext.registerReceiver(receiver, filter)
        }

        try {
            android.util.Log.e("ReaderBenchmark", "setupIteration startBenchmarkActivity for backend=$backend, fixtureMode=$fixtureMode")
            startBenchmarkActivity(backend, fixtureMode)
            val ready = latch.await(30, TimeUnit.SECONDS)
            android.util.Log.e("ReaderBenchmark", "setupIteration latch await returned ready=$ready for backend=$backend, fixtureMode=$fixtureMode")
            check(ready) { "benchmark_ready broadcast was not received within 30s for backend=$backend, fixtureMode=$fixtureMode" }
        } finally {
            try {
                targetContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {}
        }
        Thread.sleep(200)
        device.waitForIdle()

        // Verify actual refresh rate precondition (ADR 0002 Phase 0B: request 120Hz + verify actual)
        val dm = targetContext.getSystemService(DisplayManager::class.java)
        val refreshRate = dm?.getDisplay(0)?.refreshRate ?: 60f
        android.util.Log.i("ReaderProductionBenchmark", "Device display refresh rate at setup: $refreshRate Hz")
        require(refreshRate >= 119f) { "Expected ~120Hz display refresh rate, actual=$refreshRate Hz" }
    }

    private fun MacrobenchmarkScope.burstFling() {
        val midX = device.displayWidth / 2
        val topY = device.displayHeight / 5
        val bottomY = device.displayHeight * 4 / 5
        // 8 down, 8 up, 8 down (prevents stalling at bottom)
        repeat(8) { device.swipe(midX, bottomY, midX, topY, 10) }
        repeat(8) { device.swipe(midX, topY, midX, bottomY, 10) }
        repeat(8) { device.swipe(midX, bottomY, midX, topY, 10) }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.sustainedTraversal(cycles: Int) {
        val midX = device.displayWidth / 2
        val topY = device.displayHeight / 5
        val bottomY = device.displayHeight * 4 / 5
        repeat(cycles) {
            repeat(20) { device.swipe(midX, bottomY, midX, topY, 12) }
            repeat(20) { device.swipe(midX, topY, midX, bottomY, 12) }
        }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.startBenchmarkActivity(
        backend: String,
        fixtureMode: String = FIXTURE_MODE_STANDARD,
    ) {
        val intent = Intent().apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY)
            putExtra(EXTRA_BACKEND, backend)
            putExtra(EXTRA_FIXTURE_MODE, fixtureMode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivityAndWait(intent)
    }

    companion object {
        const val ACTION_BENCHMARK_READY = "org.skepsun.kototoro.BENCHMARK_READY"
        const val TARGET_PACKAGE = "org.skepsun.kototoro"
        const val TARGET_ACTIVITY =
            "org.skepsun.kototoro.reader.benchmark.ReaderProductionBenchmarkActivity"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_FIXTURE_MODE = "fixture_mode"
        const val BACKEND_LEGACY_WEBTOON = "legacy_webtoon"
        const val BACKEND_SCENE_WEBTOON = "scene_webtoon"

        const val FIXTURE_MODE_STANDARD = "standard"
        const val FIXTURE_MODE_ULTRA_LONG = "ultra_long"
    }
}

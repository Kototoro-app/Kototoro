package org.skepsun.kototoro.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderRendererBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun lazyPartial() = measure(
        BACKEND_LAZY,
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun composeScenePartial() = measure(
        BACKEND_COMPOSE_SCENE,
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun viewScenePartial() = measure(
        BACKEND_VIEW_SCENE,
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun lazyFull() = measure(BACKEND_LAZY, CompilationMode.Full())

    @Test
    fun composeSceneFull() = measure(BACKEND_COMPOSE_SCENE, CompilationMode.Full())

    @Test
    fun viewSceneFull() = measure(BACKEND_VIEW_SCENE, CompilationMode.Full())

    private fun measure(backend: String, compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            startupMode = null,
            iterations = 5,
            setupBlock = {
                pressHome()
                startBenchmarkActivity(backend)
            },
        ) {
            repeat(8) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 4 / 5,
                    device.displayWidth / 2,
                    device.displayHeight / 5,
                    12,
                )
            }
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.startBenchmarkActivity(backend: String) {
        val intent = Intent().apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY)
            putExtra(EXTRA_BACKEND, backend)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivityAndWait(intent)
    }

    private companion object {
        const val TARGET_PACKAGE = "org.skepsun.kototoro"
        const val TARGET_ACTIVITY =
            "org.skepsun.kototoro.reader.benchmark.ReaderRendererBenchmarkActivity"
        const val EXTRA_BACKEND = "backend"
        const val BACKEND_LAZY = "lazy"
        const val BACKEND_COMPOSE_SCENE = "compose_scene"
        const val BACKEND_VIEW_SCENE = "view_scene"
    }
}

package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.image.AvifAnimatedDrawable
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.render.compose.AnimatedDrawBridge
import org.skepsun.kototoro.reader.render.compose.AnimatedDrawBridgeLifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SceneAnimatedLifecycleTest {

    @Test
    fun backgroundingPausesPlaybackAndReturningResumesWithoutAnotherDraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val frames = listOf(Color.RED, Color.BLUE).map { color ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        val drawable = AvifAnimatedDrawable(frames, longArrayOf(60_000, 60_000), -1)
        val bridge = AnimatedDrawBridge()
        val composed = CountDownLatch(1)
        val intent = Intent(context, IdleProbeActivity::class.java)
            .putExtra("scene_recovery", true)
        try {
            ActivityScenario.launch<IdleProbeActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AnimatedDrawBridgeLifecycle(bridge)
                        SideEffect {
                            val pageId = PageId(123L)
                            bridge.updateVisiblePages(setOf(pageId))
                            bridge.register(pageId, drawable)
                            composed.countDown()
                        }
                    }
                }
                assertTrue("Playback composition must attach", composed.await(5, TimeUnit.SECONDS))
                scenario.onActivity { assertTrue(drawable.isRunning) }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync { assertFalse(drawable.isRunning) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { assertTrue(drawable.isRunning) }
            }
            instrumentation.runOnMainSync {
                assertFalse(drawable.isRunning)
                assertNull(drawable.callback)
            }
        } finally {
            instrumentation.runOnMainSync {
                bridge.stopAll()
                drawable.release()
            }
        }
    }
}

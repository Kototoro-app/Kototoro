package org.skepsun.kototoro.core.ui.compose

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class ImmersiveEdgeRenderingTest {

    @Test
    fun tintIsLinearAndBlurReplacesTheSharpBackgroundAtBothEdges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (blurred in listOf(false, true)) {
            for (edge in ImmersiveEdge.entries) {
                val bounds = AtomicReference<Rect>()
                val intent = Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true)
                ActivityScenario.launch<IdleProbeActivity>(intent).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            val backdrop = rememberLayerBackdrop()
                            Box(
                                modifier = Modifier
                                    .requiredSize(64.dp, 240.dp)
                                    .background(Color.White)
                                    .onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                            ) {
                                if (blurred) {
                                    Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Color.Red))
                                    Box(Modifier.matchParentSize().background(Color.White))
                                    ImmersiveEdgeBlur(backdrop, edge, 240.dp, 16.dp, Modifier.fillMaxWidth())
                                } else {
                                    ImmersiveEdgeGradient(240.dp, Color.Black, edge, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    val deadline = SystemClock.uptimeMillis() + 5_000L
                    while (bounds.get() == null && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(16L)
                    }
                    val copied = CountDownLatch(1)
                    val frame = AtomicReference<Bitmap>()
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(activity.window, bitmap, { result ->
                            if (result == PixelCopy.SUCCESS) frame.set(bitmap)
                            copied.countDown()
                        }, Handler(Looper.getMainLooper()))
                    }
                    assertTrue("PixelCopy did not finish", copied.await(5, TimeUnit.SECONDS))
                    val bitmap = requireNotNull(frame.get()) { "PixelCopy failed" }
                    val mode = if (blurred) "blur" else "tint"
                    File(context.cacheDir, "immersive-linear-$mode-${edge.name.lowercase()}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val area = requireNotNull(bounds.get())
                    assertTrue("Fixture is outside the window", area.top >= 0f && area.bottom <= bitmap.height)
                    for (fraction in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
                        val x = area.center.x.toInt()
                        val y = (area.top + area.height * fraction).toInt()
                        val actualPosition = (y + 0.5f - area.top) / area.height
                        val expected = when {
                            blurred -> 0f
                            edge == ImmersiveEdge.TOP -> actualPosition
                            else -> 1f - actualPosition
                        }
                        val actual = android.graphics.Color.green(bitmap.getPixel(x, y)) / 255f
                        assertEquals("blurred=$blurred, $edge at $fraction", expected, actual, 0.015f)
                    }
                    bitmap.recycle()
                }
            }
        }
    }
}

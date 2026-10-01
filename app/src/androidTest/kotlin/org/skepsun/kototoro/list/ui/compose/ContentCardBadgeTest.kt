package org.skepsun.kototoro.list.ui.compose

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/** Checks pixels produced by the real badge composable, without touching library data or preferences. */
@RunWith(AndroidJUnit4::class)
class ContentCardBadgeTest {

    @Test
    fun iosPinTracksThemeChanges() = verifyThemeColors(InterfaceStyle.IOS, "pin")

    @Test
    fun iosFavoriteTracksThemeChanges() = verifyThemeColors(InterfaceStyle.IOS, "favorite")

    @Test
    fun materialPinTracksThemeChanges() = verifyThemeColors(InterfaceStyle.MATERIAL_3_EXPRESSIVE, "pin")

    private fun verifyThemeColors(style: InterfaceStyle, badge: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (primary in listOf(0xFFFF77BB.toInt(), 0xFF77DDFF.toInt())) {
            val intent = Intent(instrumentation.targetContext, IdleProbeActivity::class.java)
                .putExtra("scene_recovery", true)
            ActivityScenario.launch<IdleProbeActivity>(intent).use { scenario ->
                lateinit var activity: IdleProbeActivity
                val laidOut = CountDownLatch(1)
                var badgeBounds = Rect()
                scenario.onActivity {
                    activity = it
                    it.renderBadgeFixture(style, badge, Color(primary)) { bounds ->
                        badgeBounds = bounds
                        laidOut.countDown()
                    }
                }
                assertTrue("Badge layout did not finish", laidOut.await(5, TimeUnit.SECONDS))
                // Synchronize with a submitted frame rather than sampling before Compose has drawn.
                val drawn = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }
                    activity.window.decorView.invalidate()
                }
                assertTrue("Badge frame did not commit", drawn.await(5, TimeUnit.SECONDS))
                val bitmap = Bitmap.createBitmap(
                    badgeBounds.width(), badgeBounds.height(), Bitmap.Config.ARGB_8888,
                )
                try {
                    val copied = CountDownLatch(1)
                    var copyResult = -1
                    PixelCopy.request(activity.window, badgeBounds, bitmap, {
                        copyResult = it
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                    assertTrue(copied.await(5, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, copyResult)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val themePixels = pixels.count { pixel ->
                        kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(primary)) <= 3 &&
                            kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(primary)) <= 3 &&
                            kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(primary)) <= 3
                    }
                    assertTrue(
                        "$style $badge must use the active theme primary: $themePixels matching pixels",
                        themePixels >= 10,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}

private fun ComponentActivity.renderBadgeFixture(
    style: InterfaceStyle,
    badge: String,
    primary: Color,
    onBounds: (Rect) -> Unit,
) {
    val content = Content(
        id = 1L,
        title = "Badge fixture",
        altTitles = emptySet(),
        url = "",
        publicUrl = "",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = BadgeTestSource,
    )
    val item = ContentGridModel(
        manga = content,
        override = null,
        counter = 0,
        progress = null,
        isFavorite = true,
        isSaved = false,
        isPinned = true,
    )
    setContent {
        MaterialTheme(colorScheme = darkColorScheme(primary = primary)) {
            CompositionLocalProvider(LocalInterfaceStyle provides style) {
                Box(Modifier.size(220.dp).background(Color(0xFF333333)).padding(30.dp)) {
                    ContentCardCornerBadges(
                        badges = setOf(badge),
                        item = item,
                        corner = Alignment.TopEnd,
                        cardRadius = 12.dp,
                        metrics = ContentCardBadgeMetrics(iconSize = 32.dp),
                        modifier = Modifier.onGloballyPositioned {
                            val bounds = it.boundsInWindow()
                            onBounds(
                                Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
                            )
                        },
                    )
                }
            }
        }
    }
}

private object BadgeTestSource : ContentSource {
    override val name = "BADGE_TEST"
    override val locale = "en"
    override val contentType = ContentType.MANGA
}

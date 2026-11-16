package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.progress.CircularProgressIndicator
import org.skepsun.kototoro.core.ui.progress.LinearProgressIndicator
import org.skepsun.kototoro.core.ui.progress.LocalProgressIndicatorStyle
import org.skepsun.kototoro.core.ui.progress.ProgressIndicatorStyle
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shared progress indicators turn into the Expressive wavy ones only where a wave fits: the wavy bar and ring
 * are taller and wider than the plain ones (10dp vs 4dp, 48dp vs 40dp), so their sizes tell which one was drawn.
 */
// Material reports a bar's bounds with 10dp of touch padding above and below.
private const val PlainBar = 24f
private const val WavyBar = 30f

class ExpressiveProgressIndicatorsTest {

    @OptIn(ExperimentalTestApi::class)
    private fun sizes(interfaceStyle: DesktopInterfaceStyle, style: ProgressIndicatorStyle): Map<String, Pair<Float, Float>> {
        val result = mutableMapOf<String, Pair<Float, Float>>()
        runDesktopComposeUiTest(400, 360) {
            setContent {
                DesktopTheme(DesktopAppearance.DARK, interfaceStyle) {
                    CompositionLocalProvider(LocalProgressIndicatorStyle provides style) {
                        Column {
                            LinearProgressIndicator(progress = { .6f }, modifier = Modifier.width(240.dp).testTag("bar"))
                            LinearProgressIndicator(modifier = Modifier.width(240.dp).testTag("bar-indeterminate"))
                            LinearProgressIndicator(progress = { .6f },
                                modifier = Modifier.width(240.dp).height(4.dp).testTag("bar-hairline"))
                            CircularProgressIndicator(progress = { .6f }, modifier = Modifier.testTag("ring"))
                            CircularProgressIndicator(modifier = Modifier.testTag("ring-indeterminate"))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp).testTag("ring-small"))
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.testTag("ring-custom"))
                        }
                    }
                }
            }
            waitForIdle()
            System.getenv("KOTOTORO_UI_REPORT_DIRECTORY")?.let { directory ->
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use {
                        Files.write(Path.of(directory, "progress-$interfaceStyle-$style.png"), it.bytes)
                    }
                }
            }
            listOf("bar", "bar-indeterminate", "bar-hairline", "ring", "ring-indeterminate", "ring-small", "ring-custom")
                .forEach { tag ->
                    val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
                    result[tag] = (bounds.right - bounds.left).value to (bounds.bottom - bounds.top).value
                }
        }
        return result
    }

    @Test
    fun `expressive wavy style draws waves only where they fit`() {
        val sizes = sizes(DesktopInterfaceStyle.MATERIAL, ProgressIndicatorStyle.THICK_WAVY)
        assertEquals(WavyBar, sizes.getValue("bar").second)
        assertEquals(WavyBar, sizes.getValue("bar-indeterminate").second)
        assertEquals(PlainBar, sizes.getValue("bar-hairline").second)
        assertEquals(48f to 48f, sizes.getValue("ring"))
        assertEquals(48f to 48f, sizes.getValue("ring-indeterminate"))
        assertEquals(16f to 16f, sizes.getValue("ring-small"))
        assertEquals(40f to 40f, sizes.getValue("ring-custom"))
    }

    @Test
    fun `straight style and the iOS interface keep the plain indicators`() {
        for (plain in listOf(
            sizes(DesktopInterfaceStyle.MATERIAL, ProgressIndicatorStyle.THICK_STRAIGHT),
            sizes(DesktopInterfaceStyle.IOS, ProgressIndicatorStyle.THICK_WAVY),
        )) {
            assertEquals(PlainBar, plain.getValue("bar").second)
            assertEquals(PlainBar, plain.getValue("bar-indeterminate").second)
            assertEquals(40f to 40f, plain.getValue("ring"))
            assertEquals(40f to 40f, plain.getValue("ring-indeterminate"))
        }
    }
}

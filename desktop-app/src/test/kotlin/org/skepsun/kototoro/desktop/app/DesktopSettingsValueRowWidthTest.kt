package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/**
 * The shared settings value row puts a title on the left and the current value in front of the chevron, and moves
 * the value under the title when the value does not fit beside it.
 *
 * On the device at 1.5x the decoder row read "Prefer hardware (reco..." with no "Decoder" label at all: the value
 * column used a flat 260dp cap, which ate the whole leftover width and squeezed the weighted title to zero. These
 * cases pin the row at the same 411dp phone width the emulator reports, at both font scales, plus a wide window.
 */
class DesktopSettingsValueRowWidthTest {

    private val phoneWidth = 411.dp
    private val title = "Decoder"
    private val longValue = "Prefer hardware (recommended)"
    private val shortValue = "Default"

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `long value moves under the title at the default font scale`() =
        runRowTest(phoneWidth, fontScale = 1f, value = longValue) { titleBounds, valueBounds ->
            assertTrue(
                valueBounds.top > titleBounds.top,
                "long value stayed beside the title (top ${valueBounds.top.value}dp vs ${titleBounds.top.value}dp)",
            )
            assertTrue(
                (valueBounds.left - titleBounds.left).value.absoluteValue <= 2f,
                "value is not aligned with the title (left ${valueBounds.left.value}dp vs ${titleBounds.left.value}dp)",
            )
            val valueWidth = valueBounds.right - valueBounds.left
            assertTrue(valueWidth > 200.dp, "stacked value is squeezed to ${valueWidth.value}dp")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `long value moves under the title at 1_5x font scale`() =
        runRowTest(phoneWidth, fontScale = 1.5f, value = longValue) { titleBounds, valueBounds ->
            assertTrue(
                valueBounds.top > titleBounds.top,
                "long value stayed beside the title at 1.5x (top ${valueBounds.top.value}dp)",
            )
            assertTrue(
                valueBounds.right <= phoneWidth + 1.dp,
                "value ends at ${valueBounds.right.value}dp in a ${phoneWidth.value}dp row",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `short value stays beside the title at the default font scale`() =
        runRowTest(phoneWidth, fontScale = 1f, value = shortValue) { titleBounds, valueBounds ->
            assertTrue(
                titleBounds.right <= valueBounds.left,
                "title ends at ${titleBounds.right.value}dp, value starts at ${valueBounds.left.value}dp",
            )
            assertTrue(valueBounds.right <= phoneWidth + 1.dp, "value ends at ${valueBounds.right.value}dp")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window keeps a long value beside the title at large fonts`() =
        runRowTest(1000.dp, fontScale = 1.5f, value = longValue) { titleBounds, valueBounds ->
            assertTrue(
                titleBounds.right <= valueBounds.left,
                "title ends at ${titleBounds.right.value}dp, value starts at ${valueBounds.left.value}dp",
            )
            assertTrue(valueBounds.right <= 1001.dp, "value ends at ${valueBounds.right.value}dp")
        }

    @OptIn(ExperimentalTestApi::class)
    private fun runRowTest(
        rowWidth: androidx.compose.ui.unit.Dp,
        fontScale: Float,
        value: String,
        assertions: (androidx.compose.ui.unit.DpRect, androidx.compose.ui.unit.DpRect) -> Unit,
    ) = runDesktopComposeUiTest(1200, 300) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.width(rowWidth)) {
                        SettingsValueRow(title, null, value, true, {})
                    }
                }
            }
        }
        waitForIdle()
        val titleBounds = onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val valueBounds = onNodeWithText(value, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertions(titleBounds, valueBounds)
    }
}

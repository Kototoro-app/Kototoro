package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.compose.SettingsItemGroup

/**
 * A settings segment rounds out to the group's outer corner while one of its controls is pressed. The probe pixel
 * sits 3px inside the middle row's corner: covered by the row at its resting 4dp corner, cut away at the 24dp one.
 */
class SettingsSegmentPressTest {

    /** Whether the probe pixel shows the row (true) or the canvas behind its cut corner (false), per phase. */
    @OptIn(ExperimentalTestApi::class)
    private fun cornerCovered(clickableRows: Boolean): List<Boolean> {
        val covered = mutableListOf<Boolean>()
        runDesktopComposeUiTest(400, 300) {
            setContent {
                DesktopTheme(DesktopAppearance.DARK, DesktopInterfaceStyle.MATERIAL) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsItemGroup(itemCount = 3, modifier = Modifier.width(300.dp)) { index ->
                            Box(
                                Modifier.fillMaxWidth().height(56.dp).testTag("row-$index")
                                    .then(if (clickableRows) Modifier.clickable {} else Modifier),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            fun probe() {
                val pixels = onRoot().captureToImage().toPixelMap()
                val row = onNodeWithTag("row-1").getUnclippedBoundsInRoot()
                val canvas = pixels[380, 280]
                covered += pixels[(row.left.value + 3).toInt(), (row.top.value + 3).toInt()] != canvas
            }
            probe()
            onNodeWithTag("row-1").performMouseInput { moveTo(center); press() }
            mainClock.advanceTimeBy(1_000)
            probe()
            onNodeWithTag("row-1").performMouseInput { release() }
            mainClock.advanceTimeBy(1_000)
            probe()
        }
        return covered
    }

    @Test
    fun `pressing a row's control rounds the segment and releasing restores it`() {
        assertEquals(listOf(true, false, true), cornerCovered(clickableRows = true))
    }

    @Test
    fun `a row with nothing to press keeps its corners`() {
        assertEquals(listOf(true, true, true), cornerCovered(clickableRows = false))
    }
}

package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.UiPresentationMode
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.UiPresentationConfig
import org.skepsun.kototoro.reader.ui.compose.design.ReaderAutoScrollSpeedControl

@OptIn(ExperimentalTestApi::class)
class ReaderAutoScrollSpeedControlTest {
    @Test
    fun liveEditsReachAndroidWhileDesktopWritesOnceAfterRelease() {
        for (releaseOnly in listOf(false, true)) runDesktopComposeUiTest(width = 380, height = 200) {
            var speed by mutableFloatStateOf(.25f)
            val writes = mutableListOf<Float>()
            setContent {
                DesktopTheme {
                    ReaderAutoScrollSpeedControl(speed, { speed = it; writes += it }, commitOnRelease = releaseOnly)
                }
            }
            val slider = onNodeWithTag("reader-autoscroll-speed")
            slider.performTouchInput {
                down(Offset(width * .25f, centerY))
                moveTo(Offset(width * .7f, centerY), delayMillis = 100)
            }
            runOnIdle {
                if (releaseOnly) assertTrue(writes.isEmpty())
                else { assertTrue(writes.isNotEmpty()); assertTrue(speed > .5f) }
            }
            slider.performTouchInput { up() }
            runOnIdle {
                assertTrue(speed > .5f)
                if (releaseOnly) assertEquals(1, writes.size)
            }
        }
    }

    @Test
    fun externalSpeedRestoresSliderAndDisabledStatePreventsEdits() =
        runDesktopComposeUiTest(width = 380, height = 200) {
            var speed by mutableFloatStateOf(.25f)
            var enabled by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme { ReaderAutoScrollSpeedControl(speed, { writes++ }, enabled = enabled) }
            }
            runOnIdle { speed = .8f }
            val slider = onNodeWithTag("reader-autoscroll-speed")
            assertEquals(.8f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            runOnIdle { enabled = false }
            slider.assertIsNotEnabled().performTouchInput { swipeLeft() }
            runOnIdle { assertEquals(0, writes) }
        }

    @Test
    fun tvDirectionalKeysCommitEachDiscreteEdit() =
        runDesktopComposeUiTest(width = 380, height = 200) {
            var speed by mutableFloatStateOf(.25f)
            var writes = 0
            setContent {
                DesktopTheme {
                    CompositionLocalProvider(LocalUiPresentationConfig provides
                        UiPresentationConfig(UiPresentationMode.TV, UiPresentationMode.TV)) {
                        ReaderAutoScrollSpeedControl(speed, { speed = it; writes++ })
                    }
                }
            }
            val slider = onNodeWithTag("reader-autoscroll-speed")
            slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            slider.performKeyInput { pressKey(Key.DirectionRight) }
            runOnIdle { assertEquals(.3f, speed, .001f); assertEquals(1, writes) }
        }
}

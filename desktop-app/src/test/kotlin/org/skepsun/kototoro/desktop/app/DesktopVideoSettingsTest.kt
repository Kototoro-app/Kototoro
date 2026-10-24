package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopVideoSettingsTest {
    @Test
    fun `defaults are Android's backdrop decoder and both opacities`() {
        val settings = DesktopVideoSettings()
        assertEquals(DesktopReaderBackground.DEFAULT, settings.background)
        assertEquals(DesktopVideoDecoderMode.HARDWARE_PREFERRED, settings.decoderMode)
        assertEquals(90, settings.controlsAlpha)
        assertEquals(70, settings.gradientAlpha)
        assertEquals(0.9f, settings.controlsOpacity)
        assertEquals(0.7f, settings.gradientOpacity)
        assertEquals(30..100, DesktopVideoSettings.CONTROLS_ALPHA_RANGE)
        assertEquals(0..100, DesktopVideoSettings.GRADIENT_ALPHA_RANGE)
    }

    @Test
    fun `opacities clamp a stored value the way Android's preference range does`() {
        // The controls never reach zero: Android's slider stops at 30%, so a stored 0 still shows a third of the bar.
        assertEquals(0.3f, DesktopVideoSettings(controlsAlpha = 0).controlsOpacity)
        assertEquals(1f, DesktopVideoSettings(controlsAlpha = 140).controlsOpacity)
        assertEquals(0f, DesktopVideoSettings(gradientAlpha = -20).gradientOpacity)
        assertEquals(1f, DesktopVideoSettings(gradientAlpha = 250).gradientOpacity)
    }

    @Test
    fun `the decoder modes map to libmpv's hwdec pair`() {
        val hardwareOnly = DesktopVideoDecoderMode.HARDWARE_ONLY.mpvOptions
        assertEquals("auto-safe", hardwareOnly["hwdec"])
        assertEquals("no", hardwareOnly["hwdec-fallback"])
        assertEquals(mapOf("hwdec" to "auto-safe"), DesktopVideoDecoderMode.HARDWARE_PREFERRED.mpvOptions)
        assertEquals(mapOf("hwdec" to "no"), DesktopVideoDecoderMode.SOFTWARE_PREFERRED.mpvOptions)
        assertTrue(DesktopVideoDecoderMode.entries.all { "hwdec" in it.mpvOptions })
    }
}

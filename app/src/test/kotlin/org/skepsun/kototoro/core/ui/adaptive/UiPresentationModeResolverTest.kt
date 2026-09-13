package org.skepsun.kototoro.core.ui.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.UiPresentationMode

class UiPresentationModeResolverTest {

    @Test
    fun `auto selects tv for a television ui mode`() {
        val device = UiDeviceCharacteristics(
            uiModeType = android.content.res.Configuration.UI_MODE_TYPE_TELEVISION,
        )

        assertEquals(
            UiPresentationMode.TV,
            UiPresentationModeResolver.resolve(UiPresentationMode.AUTO, device),
        )
    }

    @Test
    fun `auto tv config remains automatic and has no restore button`() {
        val config = UiPresentationModeResolver.resolveConfig(
            requestedMode = UiPresentationMode.AUTO,
            device = UiDeviceCharacteristics(hasLeanbackFeature = true),
        )

        assertEquals(UiPresentationMode.TV, config.effectiveMode)
        assertTrue(config.isTv)
        assertFalse(config.isManualTv)
        assertFalse(config.canRestoreStandard)
    }

    @Test
    fun `auto selects tv for a leanback device`() {
        val device = UiDeviceCharacteristics(hasLeanbackFeature = true)

        assertEquals(
            UiPresentationMode.TV,
            resolvePresentationMode(UiPresentationMode.AUTO, device),
        )
    }

    @Test
    fun `auto keeps standard presentation on non tv devices`() {
        val config = UiPresentationModeResolver.resolveConfig(
            requestedMode = UiPresentationMode.AUTO,
            device = UiDeviceCharacteristics(),
        )

        assertEquals(UiPresentationMode.STANDARD, config.effectiveMode)
        assertFalse(config.isTv)
        assertFalse(config.canRestoreStandard)
    }

    @Test
    fun `explicit modes override device detection`() {
        val tvDevice = UiDeviceCharacteristics(isTelevision = true)

        assertEquals(
            UiPresentationMode.STANDARD,
            UiPresentationModeResolver.resolve(UiPresentationMode.STANDARD, tvDevice),
        )
        assertEquals(
            UiPresentationMode.TV,
            UiPresentationModeResolver.resolve(UiPresentationMode.TV, UiDeviceCharacteristics()),
        )
    }

    @Test
    fun `manual tv config exposes standard mode escape hatch`() {
        val config = UiPresentationModeResolver.resolveConfig(
            requestedMode = UiPresentationMode.TV,
            device = UiDeviceCharacteristics(),
        )

        assertTrue(config.isTv)
        assertTrue(config.isManualTv)
        assertTrue(config.canRestoreStandard)
    }
}

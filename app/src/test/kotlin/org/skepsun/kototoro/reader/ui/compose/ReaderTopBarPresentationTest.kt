package org.skepsun.kototoro.reader.ui.compose

import android.os.BatteryManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderInfoBarVisibilityTest {

	@Test
	fun `enabled information bar is visible while controls are hidden`() {
		assertTrue(shouldShowReaderInfoBar(infoBarEnabled = true, controlsVisible = false))
	}

	@Test
	fun `information bar is hidden when disabled or controls are visible`() {
		assertFalse(shouldShowReaderInfoBar(infoBarEnabled = false, controlsVisible = false))
		assertFalse(shouldShowReaderInfoBar(infoBarEnabled = true, controlsVisible = true))
	}

	@Test
	fun `charging and full battery states use charging presentation`() {
		assertTrue(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_CHARGING))
		assertTrue(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_FULL))
		assertFalse(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_DISCHARGING))
		assertFalse(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_UNKNOWN))
	}
}

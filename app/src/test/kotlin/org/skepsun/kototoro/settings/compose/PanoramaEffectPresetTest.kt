package org.skepsun.kototoro.settings.compose

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class PanoramaEffectPresetTest {
	@Test
	fun `known values resolve to their preset`() {
		PanoramaLayoutMode.entries.forEach { mode ->
			PanoramaEffectPreset.entries
				.filterNot { it == PanoramaEffectPreset.CUSTOM }
				.forEach { preset ->
					val values = requireNotNull(preset.valuesFor(mode))
					resolvePanoramaEffectPreset(
						mode = mode,
						blurPercent = values.blurPercent,
						transitionRangePercent = values.transitionRangePercent,
						topOpacityPercent = values.topOpacityPercent,
					) shouldBe preset
				}
		}
	}

	@Test
	fun `full and half screen presets use independently tuned values`() {
		PanoramaEffectPreset.entries
			.filterNot { it == PanoramaEffectPreset.CUSTOM }
			.forEach { preset ->
				preset.valuesFor(PanoramaLayoutMode.FULL_SCREEN) shouldNotBe
					preset.valuesFor(PanoramaLayoutMode.HALF_SCREEN)
			}
	}

	@Test
	fun `all presets use ninety percent top opacity`() {
		PanoramaLayoutMode.entries.forEach { mode ->
			PanoramaEffectPreset.entries
				.filterNot { it == PanoramaEffectPreset.CUSTOM }
				.forEach { preset ->
					requireNotNull(preset.valuesFor(mode)).topOpacityPercent shouldBe 90
				}
		}
	}

	@Test
	fun `non preset values resolve to custom`() {
		PanoramaLayoutMode.entries.forEach { mode ->
			resolvePanoramaEffectPreset(
				mode = mode,
				blurPercent = 42,
				transitionRangePercent = 73,
				topOpacityPercent = 91,
			) shouldBe PanoramaEffectPreset.CUSTOM
		}
	}
}

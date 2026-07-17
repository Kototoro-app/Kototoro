package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.util.find

@Keep
enum class BackgroundStyle(
	@StringRes val titleResId: Int,
	@StringRes val summaryResId: Int,
) {
	DEFAULT(
		titleResId = R.string.bg_style_default,
		summaryResId = R.string.bg_style_default_summary,
	),
	DYNAMIC_ARTWORK_BLUR(
		titleResId = R.string.bg_style_artwork_blur,
		summaryResId = R.string.bg_style_artwork_blur_summary,
	),
	DYNAMIC_TONAL_GLASS(
		titleResId = R.string.bg_style_tonal_glass,
		summaryResId = R.string.bg_style_tonal_glass_summary,
	),
	SYSTEM_DYNAMIC_TINT(
		titleResId = R.string.bg_style_system_tint,
		summaryResId = R.string.bg_style_system_tint_summary,
	),
	ELEVATED_CONTAINERS(
		titleResId = R.string.bg_style_elevated_containers,
		summaryResId = R.string.bg_style_elevated_containers_summary,
	);

	companion object {
		fun safeValueOf(name: String): BackgroundStyle? {
			return BackgroundStyle.entries.find(name)
		}
	}
}

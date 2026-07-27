package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

@Keep
enum class InterfaceStyle(
	@StringRes val titleResId: Int,
	@StringRes val summaryResId: Int,
) {
	@Deprecated("Kept only to migrate preferences written by older versions")
	MATERIAL_3(
		titleResId = R.string.interface_style_material3,
		summaryResId = R.string.interface_style_material3_summary,
	),
	MATERIAL_3_EXPRESSIVE(
		titleResId = R.string.interface_style_material3_expressive,
		summaryResId = R.string.interface_style_material3_expressive_summary,
	),
	IOS(
		titleResId = R.string.interface_style_ios,
		summaryResId = R.string.interface_style_ios_summary,
	),
	;

	companion object {
		val selectableEntries: List<InterfaceStyle> = listOf(MATERIAL_3_EXPRESSIVE, IOS)
	}
}

@Suppress("DEPRECATION")
fun InterfaceStyle.normalized(): InterfaceStyle = when (this) {
	InterfaceStyle.MATERIAL_3 -> InterfaceStyle.MATERIAL_3_EXPRESSIVE
	InterfaceStyle.MATERIAL_3_EXPRESSIVE,
	InterfaceStyle.IOS,
	-> this
}

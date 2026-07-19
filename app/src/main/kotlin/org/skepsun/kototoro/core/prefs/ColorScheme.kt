package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.util.find

@Keep
	enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
) {

	DEFAULT(R.style.ThemeOverlay_Kototoro_Totoro, R.string.theme_name_totoro),
	IOS(R.style.ThemeOverlay_Kototoro_IosPalette, R.string.theme_name_ios),
	MONET(R.style.ThemeOverlay_Kototoro_Monet, R.string.theme_name_dynamic),
	EXPRESSIVE(R.style.ThemeOverlay_Kototoro_Expressive, R.string.theme_name_expressive),
	MIKU(R.style.ThemeOverlay_Kototoro_Miku, R.string.theme_name_miku),
	RENA(R.style.ThemeOverlay_Kototoro_Asuka, R.string.theme_name_asuka),
	FROG(R.style.ThemeOverlay_Kototoro_Mion, R.string.theme_name_mion),
	BLUEBERRY(R.style.ThemeOverlay_Kototoro_Rikka, R.string.theme_name_rikka),
	SAKURA(R.style.ThemeOverlay_Kototoro_Sakura, R.string.theme_name_sakura),
	MAMIMI(R.style.ThemeOverlay_Kototoro_Mamimi, R.string.theme_name_mamimi),
	KANADE(R.style.ThemeOverlay_Kototoro_Kanade, R.string.theme_name_kanade),
	ITSUKA(R.style.ThemeOverlay_Kototoro_Itsuka, R.string.theme_name_itsuka),
	;

	companion object {

		val default: ColorScheme
			get() = if (DynamicColors.isDynamicColorAvailable()) {
				MONET
			} else {
				DEFAULT
			}

		fun getAvailableList(): List<ColorScheme> {
			val list = ColorScheme.entries.toMutableList()
			if (!DynamicColors.isDynamicColorAvailable()) {
				list.remove(MONET)
				list.remove(EXPRESSIVE)
			}
			return list
		}

		fun safeValueOf(name: String): ColorScheme? {
			return ColorScheme.entries.find(name)
		}
	}
}

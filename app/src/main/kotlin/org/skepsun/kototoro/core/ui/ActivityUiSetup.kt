package org.skepsun.kototoro.core.ui

import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ColorScheme
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.util.configureSafeAreaWindow

/**
 * Applies the Kototoro theme to an activity.
 *
 * The activity always starts from the Material 3 base theme (R.style.Theme_Kototoro) so the
 * AppCompat/Material identity (e.g. colorPrimary) is preserved - Material components such as
 * Snackbar require a Theme.AppCompat (or descendant) theme. Each user-selected theme overlay
 * (color scheme, amoled, loading spinner style, popup radius, ...) is then layered on top with
 * Resources.Theme.applyStyle instead of replacing the whole theme, since the overlays are bare
 * styles that only redefine a subset of attributes and do not inherit a proper base themselves.
 */
internal fun AppCompatActivity.applyKototoroActivityTheme(settings: AppSettings) {
	setTheme(R.style.Theme_Kototoro)
	val theme = theme
	theme.applyStyle(settings.colorScheme.styleResId, true)
	if (settings.isAmoledTheme) {
		theme.applyStyle(R.style.ThemeOverlay_Kototoro_Amoled, true)
	}
	if (settings.interfaceStyle == InterfaceStyle.IOS && settings.colorScheme == ColorScheme.IOS) {
		theme.applyStyle(R.style.ThemeOverlay_Kototoro_IosPalette, true)
	}
	if (settings.interfaceStyle == InterfaceStyle.MATERIAL_3_EXPRESSIVE) {
		theme.applyStyle(R.style.ThemeOverlay_Kototoro_ExpressiveComponents, true)
	}
	when (settings.loadingCircleStyle) {
		AppSettings.LoadingCircleStyle.THICK_STRAIGHT ->
			theme.applyStyle(R.style.ThemeOverlay_Kototoro_Loading_ThickStraight, true)

		AppSettings.LoadingCircleStyle.THICK_WAVY ->
			theme.applyStyle(R.style.ThemeOverlay_Kototoro_Loading_ThickWavy, true)

		AppSettings.LoadingCircleStyle.THIN_STRAIGHT ->
			theme.applyStyle(R.style.ThemeOverlay_Kototoro_Loading_ThinStraight, true)

		AppSettings.LoadingCircleStyle.THIN_WAVY ->
			theme.applyStyle(R.style.ThemeOverlay_Kototoro_Loading_ThinWavy, true)
	}
	when (settings.popupRadius) {
		12 -> theme.applyStyle(R.style.ThemeOverlay_Kototoro_PopupRadius_12, true)
		16 -> theme.applyStyle(R.style.ThemeOverlay_Kototoro_PopupRadius_16, true)
		20 -> theme.applyStyle(R.style.ThemeOverlay_Kototoro_PopupRadius_20, true)
		24 -> theme.applyStyle(R.style.ThemeOverlay_Kototoro_PopupRadius_24, true)
	}
}

internal fun ComponentActivity.configureKototoroEdgeToEdge() {
	enableEdgeToEdge()
	WindowCompat.setDecorFitsSystemWindows(window, false)
	configureSafeAreaWindow()
}

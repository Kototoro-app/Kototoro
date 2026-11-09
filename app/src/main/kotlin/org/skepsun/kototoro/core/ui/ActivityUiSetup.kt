package org.skepsun.kototoro.core.ui

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
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

/**
 * Draws the activity behind both system bars on every API level.
 *
 * The no-argument [enableEdgeToEdge] paints a 90% white / 50% black scrim behind the navigation
 * bar on API 26-28 and turns navigation bar contrast enforcement back on for API 29+, overriding
 * the theme's `enforceNavigationBarContrast=false`. Several Android 10-12 ROMs honour that flag
 * even with gesture navigation, leaving an opaque bar unless edge-to-edge is forced system-wide.
 * API 26 cannot draw dark navigation icons, so it keeps the dim scrim the theme declares there.
 */
internal fun ComponentActivity.configureKototoroEdgeToEdge() {
    val navigationBarStyle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
    } else {
        SystemBarStyle.dark(ContextCompat.getColor(this, R.color.dim))
    }
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = navigationBarStyle,
    )
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    configureSafeAreaWindow()
}

package org.skepsun.kototoro.core.ui.glass

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle

/**
 * A composable side-effect utility that applies real-time native window blurring (FLAG_BLUR_BEHIND)
 * and deep background dimming (`dimAmount = 0.70f`) to the host [DialogWindow] whenever
 * [BackgroundStyle.DYNAMIC_ARTWORK_BLUR] is active.
 *
 * This ensures that when any [AlertDialog], [Dialog], or bottom sheet pops up over the screen,
 * the underlying activity/settings content instantly blurs out smoothly, preventing visual overlap
 * and keeping dialog text 100% sharp and readable.
 */
@Composable
fun ApplyDynamicArtworkBlurDialogStyle() {
    val backgroundStyle = LocalBackgroundStyle.current
    val view = LocalView.current

    if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
        SideEffect {
            val window = findDialogWindow(view)
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 60
                    }
                }
                window.setDimAmount(0.70f)
            }
        }
    }
}

private fun findDialogWindow(view: View): Window? {
    var current: View? = view
    while (current != null) {
        if (current is DialogWindowProvider) {
            return current.window
        }
        val parent = current.parent
        current = if (parent is View) parent else null
    }
    return (view.context as? android.app.Dialog)?.window
}

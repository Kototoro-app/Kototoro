package org.skepsun.kototoro.core.ui.util

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import org.skepsun.kototoro.core.util.ext.getThemeColor

fun Activity.configureSafeAreaWindow() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
    val surfaceColor = getThemeColor(com.google.android.material.R.attr.colorSurface, Color.BLACK)
    window.setBackgroundDrawable(ColorDrawable(surfaceColor))
    window.decorView.setBackgroundColor(surfaceColor)
}

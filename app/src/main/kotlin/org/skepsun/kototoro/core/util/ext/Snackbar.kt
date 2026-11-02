package org.skepsun.kototoro.core.util.ext

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.adaptive.resolveUiPresentationConfig

/**
 * Snackbar counterpart of [org.skepsun.kototoro.core.ui.compose.KototoroSnackbarHost] for the
 * View-based screens.
 *
 * Material Components colours a snackbar with `inverseSurface`, which is a near-white plate on the
 * dark TV chrome. On TV the snackbar takes the same surface ramp colours the Compose host uses and
 * floats as a rounded panel instead of a full-bleed bar glued to the screen edge.
 */
fun View.makeSnackbar(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar =
    Snackbar.make(this, text, duration).applyTvPalette()

fun View.makeSnackbar(@StringRes resId: Int, duration: Int = Snackbar.LENGTH_SHORT): Snackbar =
    Snackbar.make(this, resId, duration).applyTvPalette()

private fun Snackbar.applyTvPalette(): Snackbar {
    val hostView = view
    if (!hostView.context.isTvUiPresentation()) {
        return this
    }
    val background = MaterialColors.getColor(
        hostView,
        com.google.android.material.R.attr.colorSurfaceContainerHigh,
    )
    hostView.shapeAsFloatingPanel(background)
    // The tint list is what actually paints the plate: the snackbar layout carries Material's own
    // tint, and assigning a background keeps it, so the tint has to be set to our colour as well.
    setBackgroundTint(background)
    setTextColor(MaterialColors.getColor(hostView, com.google.android.material.R.attr.colorOnSurface))
    setActionTextColor(MaterialColors.getColor(hostView, androidx.appcompat.R.attr.colorPrimary))
    return this
}

/** Rounds the snackbar plate and insets it from the screen edges. */
private fun View.shapeAsFloatingPanel(fillColor: Int) {
    val layout = this as? Snackbar.SnackbarLayout ?: return
    val density = resources.displayMetrics.density
    layout.background = MaterialShapeDrawable(
        ShapeAppearanceModel.builder()
            .setAllCornerSizes(PANEL_CORNER_DP * density)
            .build(),
    ).apply {
        this.fillColor = ColorStateList.valueOf(fillColor)
    }
    val margin = (PANEL_MARGIN_DP * density).toInt()
    val params = layout.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.marginStart = margin
    params.marginEnd = margin
    params.bottomMargin = margin
    layout.layoutParams = params
}

private const val PANEL_CORNER_DP = 16f
private const val PANEL_MARGIN_DP = 24f

private fun Context.isTvUiPresentation(): Boolean = resolveUiPresentationConfig(
    context = this,
    settings = AppSettings(applicationContext),
).isTv

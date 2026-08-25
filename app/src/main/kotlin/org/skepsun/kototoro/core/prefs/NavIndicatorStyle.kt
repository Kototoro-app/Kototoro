package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

/**
 * How the selected item is highlighted in the floating navigation bar.
 *
 * This is the single source of truth for two pre-2.1 options that were
 * mutually exclusive but stored as independent booleans (the expressive pill
 * and the full-width capsule indicator), which let users end up with both
 * enabled at once:
 *
 *  - [LABELS_BELOW] puts every label below its icon (the classic /
 *    AndroidLiquidGlass LiquidBottomTabs look). Whether the bar spans the full
 *    width is controlled separately by the full-width navigation bar toggle.
 *  - [LABELS_RIGHT] uses a pill-shaped selected item whose label expands to
 *    the right of the icon.
 */
@Keep
enum class NavIndicatorStyle(
    @StringRes val titleResId: Int,
) {
    LABELS_BELOW(R.string.pref_nav_indicator_style_labels_below),
    LABELS_RIGHT(R.string.pref_nav_indicator_style_labels_right),
    ;
}

package org.skepsun.kototoro.core.ui.widgets

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Shared UI model for a filter chip. Survived the removal of the legacy [ChipsView]
 * View so Compose screens can reuse the same chip descriptors across list sources.
 */
data class ChipModel(
    val title: CharSequence? = null,
    @StringRes val titleResId: Int = 0,
    @DrawableRes val icon: Int = 0,
    val iconData: Any? = null,
    @ColorRes val tint: Int = 0,
    val counter: Int = 0,
    val isChecked: Boolean = false,
    val isLoading: Boolean = false,
    val isDropdown: Boolean = false,
    val isCloseable: Boolean = false,
    val data: Any? = null,
)

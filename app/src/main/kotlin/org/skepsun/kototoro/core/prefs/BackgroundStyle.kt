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
    // Legacy values are retained so existing serialized preferences remain readable.
    // Surface material now follows InterfaceStyle; these values normalize to DEFAULT.
    DYNAMIC_TONAL_GLASS(
        titleResId = R.string.bg_style_default,
        summaryResId = R.string.bg_style_default_summary,
    ),
    SYSTEM_DYNAMIC_TINT(
        titleResId = R.string.bg_style_default,
        summaryResId = R.string.bg_style_default_summary,
    ),
    ELEVATED_CONTAINERS(
        titleResId = R.string.bg_style_default,
        summaryResId = R.string.bg_style_default_summary,
    );

    fun normalized(): BackgroundStyle = when (this) {
        DEFAULT,
        DYNAMIC_ARTWORK_BLUR,
        -> this
        DYNAMIC_TONAL_GLASS,
        SYSTEM_DYNAMIC_TINT,
        ELEVATED_CONTAINERS,
        -> DEFAULT
    }

    val usesLayeredNavigationSurface: Boolean
        get() = this == ELEVATED_CONTAINERS

    companion object {
        val selectableEntries: List<BackgroundStyle> = listOf(
            DEFAULT,
            DYNAMIC_ARTWORK_BLUR,
        )

        fun safeValueOf(name: String): BackgroundStyle? {
            return BackgroundStyle.entries.find(name)
        }
    }
}

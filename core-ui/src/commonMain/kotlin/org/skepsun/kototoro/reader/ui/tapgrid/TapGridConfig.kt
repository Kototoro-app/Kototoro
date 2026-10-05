package org.skepsun.kototoro.reader.ui.tapgrid

import org.skepsun.kototoro.reader.domain.TapGridArea

/** The tap and long-tap actions of one tap-grid area; `null` means the area does nothing. */
data class TapActions(
    val tapAction: TapAction?,
    val longTapAction: TapAction?,
) {

    fun get(isLongTap: Boolean): TapAction? = if (isLongTap) longTapAction else tapAction

    fun with(isLongTap: Boolean, action: TapAction?): TapActions =
        if (isLongTap) copy(longTapAction = action) else copy(tapAction = action)
}

/** The reader's tap-grid configuration: preference keys and the default layout, shared by every platform. */
object TapGridConfig {

    const val KEY_INIT = "_init"
    const val SUFFIX_LONG = "_long"

    private val NONE = TapActions(null, null)

    /**
     * The default layout: the left column and the top centre go back, the right column and the bottom centre go
     * forward, the centre toggles the controls and opens the menu on a long tap.
     */
    val defaults: Map<TapGridArea, TapActions> = TapGridArea.entries.associateWith { area ->
        when (area) {
            TapGridArea.TOP_LEFT,
            TapGridArea.TOP_CENTER,
            TapGridArea.CENTER_LEFT,
            TapGridArea.BOTTOM_LEFT -> TapActions(TapAction.PAGE_PREV, null)

            TapGridArea.CENTER -> TapActions(TapAction.TOGGLE_UI, TapAction.SHOW_MENU)

            TapGridArea.TOP_RIGHT,
            TapGridArea.CENTER_RIGHT,
            TapGridArea.BOTTOM_CENTER,
            TapGridArea.BOTTOM_RIGHT -> TapActions(TapAction.PAGE_NEXT, null)
        }
    }

    /** Every area without an action ("disable all"). */
    val disabled: Map<TapGridArea, TapActions> = TapGridArea.entries.associateWith { NONE }

    fun prefKey(area: TapGridArea, isLongTap: Boolean): String =
        if (isLongTap) area.name + SUFFIX_LONG else area.name

    fun action(config: Map<TapGridArea, TapActions>, area: TapGridArea, isLongTap: Boolean): TapAction? =
        config[area]?.get(isLongTap)

    fun with(
        config: Map<TapGridArea, TapActions>,
        area: TapGridArea,
        isLongTap: Boolean,
        action: TapAction?,
    ): Map<TapGridArea, TapActions> = config + (area to (config[area] ?: NONE).with(isLongTap, action))
}

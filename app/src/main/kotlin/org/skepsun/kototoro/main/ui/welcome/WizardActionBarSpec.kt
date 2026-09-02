package org.skepsun.kototoro.main.ui.welcome

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizing rules of the setup-wizard action bar. Pure arithmetic over measured
 * widths (the same shape as `resolveBottomNavLayout`) so the fit decision is
 * unit-testable without a device.
 *
 * The bar is `[back] [page dots] [primary action]`. The back button and the
 * primary action are immovable: a squeezed action wraps its label inside the
 * fixed-height pill, which reads as clipped text. The page dots are therefore
 * the only member allowed to yield — and yielding means leaving, not shrinking:
 * a half-clipped rail looks broken, while `WizardPageHeader` already carries
 * "Step N of M", so dropping the rail loses nothing.
 */

/** Gap between the back button, the page dots and the primary action. */
internal val WizardActionBarItemSpacing = 16.dp

/**
 * One slot per page: every page owns a slot, so the rail never shifts as the page
 * changes. 6 pages * 14dp + 5 * 4dp = 104dp, which is the widest rail that still
 * shares a 360dp phone with a back button and a CJK "下一步" action.
 */
internal val WizardPageDotsSlotWidth = 14.dp

internal val WizardPageDotsHeight = 8.dp

internal val WizardPageDotsSlotGap = 4.dp

internal enum class WizardProgressPresentation {
    /** Back, rail and action with a gap on both sides of the rail. */
    Roomy,

    /** No room for the rail: back and action sit next to each other, centered. */
    Compact,
}

internal data class WizardActionBarSpec(
    val progress: WizardProgressPresentation,
    /**
     * Laid-out content width. It hugs the content in both presentations — the
     * pill never stretches with an empty hole where the rail used to be — and is
     * clamped to the available width.
     */
    val width: Dp,
)

/**
 * Fit the wizard action bar into [availableWidth] from the children's measured
 * (natural) widths. [dotsWidth] is `0.dp` when there is nothing to show.
 */
internal fun resolveWizardActionBar(
    availableWidth: Dp,
    backButtonWidth: Dp,
    dotsWidth: Dp,
    actionWidth: Dp,
    itemSpacing: Dp = WizardActionBarItemSpacing,
): WizardActionBarSpec {
    val compactWidth = backButtonWidth + itemSpacing + actionWidth
    val roomyWidth = backButtonWidth + itemSpacing + dotsWidth + itemSpacing + actionWidth
    val roomy = dotsWidth > 0.dp && roomyWidth <= availableWidth
    return if (roomy) {
        WizardActionBarSpec(WizardProgressPresentation.Roomy, roomyWidth.coerceAtMost(availableWidth))
    } else {
        WizardActionBarSpec(WizardProgressPresentation.Compact, compactWidth.coerceAtMost(availableWidth))
    }
}

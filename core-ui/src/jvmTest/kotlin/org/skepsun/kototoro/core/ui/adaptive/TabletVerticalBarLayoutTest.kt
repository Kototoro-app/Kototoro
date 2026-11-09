package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabletVerticalBarLayoutTest {
    private fun layout(height: Int, labels: Boolean = false) = resolveVerticalBarLayout(
        availableHeight = height.dp,
        hasHeader = true,
        hasAccessory = true,
        itemCount = 5,
        showLabels = labels,
    )

    @Test
    fun `a tall bar keeps every control at full size`() {
        // (56 + 12) * 2 + 48 * 5 + 8 = 384
        assertEquals(VerticalBarLayout(56.dp, 12.dp, 48.dp), layout(384))
    }

    @Test
    fun `labels are the first thing a shorter bar gives up`() {
        // (56 + 12) * 2 + 58 * 5 + 8 = 434
        assertEquals(58.dp, layout(434, labels = true).itemHeight)
        assertEquals(48.dp, layout(433, labels = true).itemHeight)
    }

    @Test
    fun `a phone in landscape shrinks the switcher and continue reading instead of dropping them`() {
        // The folded M332BF in landscape leaves about 307dp: (40 + 6) * 2 + 40 * 5 + 8 = 300.
        assertEquals(VerticalBarLayout(48.dp, 8.dp, 44.dp), layout(340))
        assertEquals(VerticalBarLayout(40.dp, 6.dp, 40.dp), layout(307))
    }

    @Test
    fun `an even shorter bar stays at the smallest step`() {
        assertEquals(VerticalBarLayout(40.dp, 6.dp, 40.dp), layout(200))
    }
}

class TabletVerticalBarActionsFitTest {
    private fun fits(height: Int, accessory: Boolean = true) = tabletVerticalBarFitsActions(
        availableHeight = height.dp,
        hasHeader = true,
        actionsHeight = 140.dp,
        hasAccessory = accessory,
        itemCount = 5,
        showLabels = false,
    )

    @Test
    fun `a tablet-height bar takes the actions`() {
        // 68 + 140 + 12 + 68 + 248 = 536
        assertTrue(fits(536))
    }

    @Test
    fun `a short bar leaves the actions in the top bar instead of dropping the accessory`() {
        assertFalse(fits(535))
        assertTrue(fits(468, accessory = false))
    }
}

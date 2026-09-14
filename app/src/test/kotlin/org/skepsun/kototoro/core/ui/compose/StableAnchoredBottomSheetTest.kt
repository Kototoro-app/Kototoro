package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StableAnchoredBottomSheetTest {

    @Test
    fun `sheet width is capped on wide windows`() {
        assertEquals(760.dp, calculateStableSheetWidth(1280.dp, 760.dp))
    }

    @Test
    fun `sheet width still fills narrow windows`() {
        assertEquals(360.dp, calculateStableSheetWidth(360.dp, 760.dp))
    }

    @Test
    fun `sheet width remains unconstrained without a maximum`() {
        assertEquals(1280.dp, calculateStableSheetWidth(1280.dp, null))
    }
}

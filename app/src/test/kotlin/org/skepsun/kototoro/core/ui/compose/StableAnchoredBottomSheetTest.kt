package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `sheets without a peek keep the half anchor`() {
        assertEquals(
            mapOf(
                StableSheetAnchor.Full to 0f,
                StableSheetAnchor.ThreeQuarter to 250f,
                StableSheetAnchor.Middle to 500f,
                StableSheetAnchor.Hidden to 1000f,
            ),
            stableSheetAnchorOffsets(hostHeightPx = 1000f, peekHeightPx = null),
        )
    }

    @Test
    fun `peek anchor shows exactly the peek height`() {
        assertEquals(640f, stableSheetAnchorOffsets(1000f, 360f)[StableSheetAnchor.Middle])
    }

    @Test
    fun `a peek taller than sixty percent drops the middle anchor`() {
        val offsets = stableSheetAnchorOffsets(1000f, 700f)
        assertNull(offsets[StableSheetAnchor.Middle])
        assertEquals(250f, offsets[StableSheetAnchor.ThreeQuarter])
    }

    @Test
    fun `an unmeasured peek uses the half anchor`() {
        assertEquals(500f, stableSheetAnchorOffsets(1000f, 0f)[StableSheetAnchor.Middle])
    }

    @Test
    fun `peek sheets open at the middle anchor, others at three quarters`() {
        assertEquals(StableSheetAnchor.Middle, stableSheetInitialAnchor(usePeekAnchor = true))
        assertEquals(StableSheetAnchor.ThreeQuarter, stableSheetInitialAnchor(usePeekAnchor = false))
    }
}

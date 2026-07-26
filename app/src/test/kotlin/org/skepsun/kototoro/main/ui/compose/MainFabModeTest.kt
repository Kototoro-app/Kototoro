package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainFabModeTest {

    @Test
    fun `continue reading is used when available`() {
        assertEquals(
            MainFabMode.CONTINUE_READING,
            resolveMainFabMode(resumeEnabled = true),
        )
    }

    @Test
    fun `fab is hidden when neither action is available`() {
        assertEquals(
            MainFabMode.HIDDEN,
            resolveMainFabMode(resumeEnabled = false),
        )
    }
}

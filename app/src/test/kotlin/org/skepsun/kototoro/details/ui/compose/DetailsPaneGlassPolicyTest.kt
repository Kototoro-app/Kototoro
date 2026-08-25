package org.skepsun.kototoro.details.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DetailsPaneGlassPolicyTest {

    @Test
    fun `details pane does not apply container press feedback`() {
        assertFalse(DETAILS_PANE_PRESS_FEEDBACK_ENABLED)
    }
}

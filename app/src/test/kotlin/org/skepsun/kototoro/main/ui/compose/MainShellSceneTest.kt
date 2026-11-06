package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainShellSceneTest {

    @Test
    fun `landscape navigation inset is reserved for visible top level route`() {
        assertTrue(
            shouldApplyLandscapeNavigationInset(
                isLandscapeNavigation = true,
                isTopLevelEntry = true,
            ),
        )
    }

    @Test
    fun `landscape navigation inset is not reserved for secondary route`() {
        assertFalse(
            shouldApplyLandscapeNavigationInset(
                isLandscapeNavigation = true,
                isTopLevelEntry = false,
            ),
        )
    }

    @Test
    fun `portrait navigation does not reserve landscape inset`() {
        assertFalse(
            shouldApplyLandscapeNavigationInset(
                isLandscapeNavigation = false,
                isTopLevelEntry = true,
            ),
        )
    }
}

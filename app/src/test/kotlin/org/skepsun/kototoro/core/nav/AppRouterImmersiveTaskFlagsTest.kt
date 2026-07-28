package org.skepsun.kototoro.core.nav

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppRouterImmersiveTaskFlagsTest {

    @Test
    fun `immersive spaces keep readers in the caller task`() {
        immersiveTaskFlags(enabled = true) shouldBe 0
    }

    @Test
    fun `disabled immersive spaces preserve the existing activity launch behavior`() {
        immersiveTaskFlags(enabled = false) shouldBe 0
    }
}

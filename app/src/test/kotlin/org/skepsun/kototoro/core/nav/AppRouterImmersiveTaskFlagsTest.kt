package org.skepsun.kototoro.core.nav

import android.content.Intent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppRouterImmersiveTaskFlagsTest {

    @Test
    fun `immersive spaces launch each reader in its own hidden document task`() {
        val expectedFlags =
            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

        immersiveTaskFlags(enabled = true) shouldBe expectedFlags
    }

    @Test
    fun `disabled immersive spaces preserve the existing activity launch behavior`() {
        immersiveTaskFlags(enabled = false) shouldBe 0
    }
}

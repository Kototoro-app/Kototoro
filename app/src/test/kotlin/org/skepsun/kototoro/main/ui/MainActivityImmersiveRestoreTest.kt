package org.skepsun.kototoro.main.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces

class MainActivityImmersiveRestoreTest {

    @Test
    fun `main task restores active immersive task when returning to app`() {
        shouldRestoreImmersiveSessionFromMain(
            immersiveSwitchEnabled = true,
            hasActiveSession = true,
            transitionSuppressionTarget = null,
        ) shouldBe true
    }

    @Test
    fun `main task does not race an internal space transition`() {
        shouldRestoreImmersiveSessionFromMain(
            immersiveSwitchEnabled = true,
            hasActiveSession = true,
            transitionSuppressionTarget = BuiltInSpaces.Novel,
        ) shouldBe false
    }

    @Test
    fun `main task stays visible without an active immersive session`() {
        shouldRestoreImmersiveSessionFromMain(
            immersiveSwitchEnabled = true,
            hasActiveSession = false,
            transitionSuppressionTarget = null,
        ) shouldBe false
    }
}

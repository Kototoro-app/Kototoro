package org.skepsun.kototoro.core.ui.theme

import androidx.compose.ui.graphics.luminance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class AmoledSurfaceColorsTest {

    @Test
    fun `amoled canvas remains true black`() {
        AmoledSurfaceColors.background shouldBe AmoledSurfaceColors.containerLowest
        AmoledSurfaceColors.background.luminance() shouldBe 0f
    }

    @Test
    fun `amoled containers form a strictly increasing surface hierarchy`() {
        val luminance = listOf(
            AmoledSurfaceColors.containerLowest,
            AmoledSurfaceColors.containerLow,
            AmoledSurfaceColors.container,
            AmoledSurfaceColors.containerHigh,
            AmoledSurfaceColors.containerHighest,
            AmoledSurfaceColors.bright,
        ).map { it.luminance() }

        luminance.zipWithNext().all { (lower, higher) -> lower < higher } shouldBe true
        AmoledSurfaceColors.container shouldNotBe AmoledSurfaceColors.background
    }
}

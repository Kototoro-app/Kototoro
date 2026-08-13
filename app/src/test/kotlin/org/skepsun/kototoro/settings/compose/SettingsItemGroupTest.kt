package org.skepsun.kototoro.settings.compose

import androidx.compose.ui.graphics.Color
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.InterfaceStyle

class SettingsItemGroupTest {

    @Test
    fun `single item owns the complete group outline`() {
        resolveSettingsGroupItemPosition(index = 0, total = 1) shouldBe SettingsGroupItemPosition.SINGLE
    }

    @Test
    fun `multiple items resolve stable outer and inner positions`() {
        List(4) { index -> resolveSettingsGroupItemPosition(index, total = 4) } shouldBe listOf(
            SettingsGroupItemPosition.FIRST,
            SettingsGroupItemPosition.MIDDLE,
            SettingsGroupItemPosition.MIDDLE,
            SettingsGroupItemPosition.LAST,
        )
    }

    @Test
    fun `invalid group coordinates fail fast`() {
        shouldThrow<IllegalArgumentException> {
            resolveSettingsGroupItemPosition(index = 0, total = 0)
        }
        shouldThrow<IllegalArgumentException> {
            resolveSettingsGroupItemPosition(index = -1, total = 1)
        }
        shouldThrow<IllegalArgumentException> {
            resolveSettingsGroupItemPosition(index = 2, total = 2)
        }
    }

    @Test
    fun `group scope preserves declared items and conditional visibility`() {
        val scope = SettingsItemGroupScope()

        scope.item {}
        if (false) {
            scope.item {}
        }
        scope.item {}

        scope.items.size shouldBe 2
    }

    @Test
    fun `iOS group items preserve the semantic color with a translucent alpha`() {
        val surfaceColor = Color(0xFF336699)

        val result = settingsGroupItemContainerColor(InterfaceStyle.IOS, surfaceColor)

        result.red shouldBe surfaceColor.red
        result.green shouldBe surfaceColor.green
        result.blue shouldBe surfaceColor.blue
        result.alpha shouldBe (0.74f plusOrMinus 0.002f)
    }

    @Test
    fun `Material group items keep the stable semantic surface`() {
        val surfaceColor = Color(0xFF336699)

        settingsGroupItemContainerColor(
            InterfaceStyle.MATERIAL_3_EXPRESSIVE,
            surfaceColor,
        ) shouldBe surfaceColor
    }
}

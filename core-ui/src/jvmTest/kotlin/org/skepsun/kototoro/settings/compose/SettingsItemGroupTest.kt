package org.skepsun.kototoro.settings.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SettingsItemGroupTest {

    @Test
    fun `single item owns the complete group outline`() {
        assertEquals(SettingsGroupItemPosition.SINGLE, resolveSettingsGroupItemPosition(index = 0, total = 1))
    }

    @Test
    fun `multiple items resolve stable outer and inner positions`() {
        assertEquals(
            listOf(
                SettingsGroupItemPosition.FIRST,
                SettingsGroupItemPosition.MIDDLE,
                SettingsGroupItemPosition.MIDDLE,
                SettingsGroupItemPosition.LAST,
            ),
            List(4) { index -> resolveSettingsGroupItemPosition(index, total = 4) },
        )
    }

    @Test
    fun `invalid group coordinates fail fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveSettingsGroupItemPosition(index = 0, total = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveSettingsGroupItemPosition(index = -1, total = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
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

        assertEquals(2, scope.items.size)
    }

}

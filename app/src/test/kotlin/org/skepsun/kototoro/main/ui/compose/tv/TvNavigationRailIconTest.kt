package org.skepsun.kototoro.main.ui.compose.tv

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.NavItem

class TvNavigationRailIconTest {

    @Test
    fun `tv navigation maps selector icons to static drawable resources`() {
        NavItem.entries.forEach { item ->
            assertNotEquals(
                item.icon,
                tvNavigationIconResId(item.id, isSelected = false),
                "TV navigation must not pass ${item.name} selector drawable to painterResource",
            )
            assertNotEquals(
                item.icon,
                tvNavigationIconResId(item.id, isSelected = true),
                "TV navigation must not pass ${item.name} selector drawable to painterResource",
            )
        }
    }
}

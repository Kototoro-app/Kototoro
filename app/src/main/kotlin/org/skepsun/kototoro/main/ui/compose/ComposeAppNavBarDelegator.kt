package org.skepsun.kototoro.main.ui.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.widgets.BadgeInfo
import org.skepsun.kototoro.core.ui.widgets.BottomNavState

/**
 * Bridges the few remaining non-Compose entry points (MainActivity bookkeeping:
 * main-nav items, label visibility, feed badge, destination sync) into the
 * Compose bottom nav's state flow. Navigation itself is driven directly by
 * [KototoroApp]; this class only feeds the shared [BottomNavState].
 */
class ComposeAppNavBarDelegator(
    val stateFlow: MutableStateFlow<BottomNavState>,
) {

    var showLabels: Boolean
        get() = stateFlow.value.showLabels
        set(value) {
            stateFlow.update { it.copy(showLabels = value) }
        }

    fun setupMenu(items: List<NavItem>) {
        val currentSelectedId = stateFlow.value.selectedItemId
        val targetSelectedId = items.firstOrNull { it.id == currentSelectedId }?.id
            ?: items.firstOrNull()?.id
            ?: 0
        stateFlow.value = stateFlow.value.copy(
            items = items,
            selectedItemId = targetSelectedId,
        )
    }

    fun setBadgeNumber(itemId: Int, number: Int) {
        val badges = stateFlow.value.badges.toMutableMap()
        val current = badges[itemId] ?: BadgeInfo()
        badges[itemId] = current.copy(number = number, isVisible = true)
        stateFlow.value = stateFlow.value.copy(badges = badges)
    }

    fun clearBadge(itemId: Int) {
        val badges = stateFlow.value.badges.toMutableMap()
        val current = badges[itemId] ?: BadgeInfo()
        badges[itemId] = current.copy(number = 0, isVisible = false)
        stateFlow.value = stateFlow.value.copy(badges = badges)
    }

    fun syncSelectedItem(id: Int) {
        if (stateFlow.value.selectedItemId != id) {
            stateFlow.value = stateFlow.value.copy(selectedItemId = id)
        }
    }
}

package org.skepsun.kototoro.settings.nav.model

import androidx.annotation.StringRes
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.list.ui.model.ListModel

data class NavItemConfigModel(
    val item: NavItem,
    @StringRes val disabledHintResId: Int,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is NavItemConfigModel && other.item == item
    }
}

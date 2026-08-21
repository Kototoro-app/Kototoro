package org.skepsun.kototoro.settings.nav.model

import org.skepsun.kototoro.list.ui.model.ListModel

data class NavItemAddModel(
    val canAdd: Boolean,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean = other is NavItemAddModel
}

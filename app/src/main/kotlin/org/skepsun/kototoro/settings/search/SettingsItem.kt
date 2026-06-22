package org.skepsun.kototoro.settings.search

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.settings.SettingsDestination

data class SettingsItem(
	val key: String,
	val title: CharSequence,
	val breadcrumbs: List<String>,
	val destination: SettingsDestination,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SettingsItem && other.key == key && other.destination == destination
	}
}

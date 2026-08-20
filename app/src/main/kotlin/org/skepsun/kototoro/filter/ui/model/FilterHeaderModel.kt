package org.skepsun.kototoro.filter.ui.model

import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.parsers.model.SortOrder

data class FilterHeaderModel(
	val chips: Collection<ChipModel>,
	val sortOrder: SortOrder?,
	val isFilterApplied: Boolean,
) {

	val textSummary: String
		get() = chips.mapNotNull { if (it.isChecked) it.title else null }.joinToString()
}

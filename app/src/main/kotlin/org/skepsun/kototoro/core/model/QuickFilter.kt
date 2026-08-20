package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.list.domain.ListFilterOption

fun ListFilterOption.toChipModel(isChecked: Boolean) = ChipModel(
	title = titleText,
	titleResId = titleResId,
	icon = iconResId,
	iconData = getIconData(),
	isChecked = isChecked,
	counter = if (this is ListFilterOption.Branch) chaptersCount else 0,
	data = this,
)

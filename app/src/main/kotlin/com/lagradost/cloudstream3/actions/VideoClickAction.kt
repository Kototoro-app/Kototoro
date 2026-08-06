package com.lagradost.cloudstream3.actions

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

/** Host-side ABI for actions registered by Cloudstream plugins. */
abstract class VideoClickAction {

	abstract val name: UiText

	open val oneSource: Boolean = false
	open val isPlayer: Boolean = false
	open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()
	var sourcePlugin: String? = null

	fun uniqueId(): String = "$sourcePlugin:${this::class.qualifiedName}"
}

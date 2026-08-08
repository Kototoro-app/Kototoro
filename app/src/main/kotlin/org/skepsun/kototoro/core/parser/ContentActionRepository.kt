package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.parsers.model.Content

interface ContentActionRepository {

	fun isAction(content: Content): Boolean

	fun requiresActivityHost(content: Content): Boolean = false

	suspend fun executeAction(content: Content): ContentActionResult?
}

data class ContentActionResult(
	val message: String?,
)

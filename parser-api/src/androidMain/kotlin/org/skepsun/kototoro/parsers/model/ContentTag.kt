package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.ContentParser

public data class ContentTag(
	/**
	 * User-readable tag title, should be in Title case
	 */
	@JvmField public val title: String,
	/**
	 * Identifier of a tag, must be unique among the source.
	 * @see ContentParser.getList
	 */
	@JvmField public val key: String,
	@JvmField public val source: ContentSource,
)

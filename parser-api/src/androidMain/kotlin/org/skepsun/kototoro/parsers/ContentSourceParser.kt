package org.skepsun.kototoro.parsers

import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Annotate each [ContentParser] implementation with this annotation, used by codegen
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ContentSourceParser(
	/**
	 * Name of content source. Used as an Enum value, must be UPPER_CASE and unique.
	 */
	val name: String,
	/**
	 * User-friendly title of content source. In most case equals the website name.
	 * Avoid extra whitespaces between the words if it is not required.
	 */
	val title: String,
	/**
	 * Language code (for example "en" or "ru") or blank if parser provide content on different languages.
	 */
	val locale: String = "",
	/**
	 * Type of content provided by parser. See [ContentType] for more info
	 */
	val type: ContentType = ContentType.MANGA,
)

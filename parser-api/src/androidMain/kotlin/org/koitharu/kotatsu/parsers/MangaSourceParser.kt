package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.model.ContentType

/**
 * Annotate each [MangaParser] implementation with this annotation, used by codegen
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class MangaSourceParser(
	/**
	 * Name of manga source. Used as an Enum value, must be UPPER_CASE and unique.
	 */
	val name: String,
	/**
	 * User-friendly title of manga source. In most case equals the website name.
	 * Avoid extra whitespaces between the words if it is not required.
	 */
	val title: String,
	/**
	 * Language code (for example "en" or "ru") or blank if parser provide manga on different languages.
	 */
	val locale: String = "",
	/**
	 * Type of content provided by parser. See [ContentType] for more info
	 */
	val type: ContentType = ContentType.MANGA,
)

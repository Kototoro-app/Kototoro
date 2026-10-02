package org.skepsun.kototoro.parsers.model.search

import org.skepsun.kototoro.parsers.model.*
import java.util.*

/**
 * Represents the various fields that can be used for searching manga.
 * Each field is associated with a specific data type that defines its expected values.
 *
 * @property type The Java class representing the expected type of values for this field.
 */
@Deprecated("Too complex")
public enum class SearchableField(public val type: Class<*>) {
	TITLE_NAME(String::class.java),
	TAG(ContentTag::class.java),
	AUTHOR(ContentTag::class.java),
	LANGUAGE(Locale::class.java),
	ORIGINAL_LANGUAGE(Locale::class.java),
	STATE(ContentState::class.java),
	CONTENT_TYPE(ContentType::class.java),
	CONTENT_RATING(ContentRating::class.java),
	DEMOGRAPHIC(Demographic::class.java),
	PUBLICATION_YEAR(Int::class.javaObjectType);
}

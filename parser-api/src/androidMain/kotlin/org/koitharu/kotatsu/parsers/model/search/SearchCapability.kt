package org.koitharu.kotatsu.parsers.model.search

import kotlin.reflect.KClass

/**
 * Defines the search capabilities of a given field in the manga search query.
 *
 * @property field The searchable field that this capability applies to.
 * 		Example values:
 * 		- `SearchableField.TITLE_NAME` for searching by title.
 * 		- `SearchableField.AUTHOR` for searching by author names.
 * 		- `SearchableField.TAG` for filtering by tags.
 * @property criteriaTypes The set of supported criteria types for the field.
 *      Example values:
 *      - `setOf(Include::class, Exclude::class)` selected field supports inclusion/exclusion criteria.
 *      - `setOf(Range::class)` selected field support numerical range criteria.
 * @property isMultiValue Indicates whether the field supports multiple values.
 *      - `true` if multiple values can be provided (e.g., multiple tags or authors).
 *      - `false` if only a single value is allowed (e.g., only one tag or author).
 * @property isExclusive Specifies whether the field can be used alongside other criteria.
 *      - `true` if this field can be used with other search criteria.
 *      - `false` if using this field requires it to be the only criterion in query.
 */
@Deprecated("Too complex")
public data class SearchCapability(
	/** The searchable field that this capability applies to. */
	@JvmField public val field: SearchableField,
	/** The set of supported criteria types for this field. */
	@JvmField public val criteriaTypes: Set<KClass<out QueryCriteria<*>>>,
	/** Indicates whether the field supports multiple values. */
	@JvmField public val isMultiple: Boolean,
	/** Specifies whether the field can be used alongside other criteria. */
	@JvmField public val isExclusive: Boolean = false,
)

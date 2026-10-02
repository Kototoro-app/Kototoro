package org.koitharu.kotatsu.parsers.model.search

/**
 * Represents a generic search criterion used for filtering manga search results.
 * Each criterion applies a specific condition to a [SearchableField] and operates on values of type [T].
 *
 * @param T The type of value associated with the search criterion.
 * @property field The field to which this search criterion applies.
 */
@Deprecated("Too complex")
public sealed interface QueryCriteria<T> {

	public val field: SearchableField

	override fun equals(other: Any?): Boolean

	override fun hashCode(): Int

	/**
	 * Represents an inclusion criterion that allows search results based on a set of allowed values.
	 *
	 * @param T The type of value being included in the search.
	 * @property values The set of values that should be included in the search results.
	 *
	 * ### Example Usage:
	 * ```kotlin
	 * val genreFilter = QueryCriteria.Include(SearchableField.STATE, setOf(MangaState.ONGOING, MangaState.FINISHED))
	 * ```
	 */
	public data class Include<T : Any>(
		public override val field: SearchableField,
		@JvmField public val values: Set<T>,
	) : QueryCriteria<T> {

		init {
			check(values.all { x -> field.type.isInstance(x) })
		}
	}

	/**
	 * Represents an exclusion criterion that exclude results containing certain values.
	 *
	 * @param T The type of value being excluded from the search.
	 * @property values The set of values that should be excluded from the search results.
	 *
	 * ### Example Usage:
	 * ```kotlin
	 * val excludeTag = QueryCriteria.Exclude(SearchableField.TAG, setOf(MangaTag(key, title, source)))
	 * ```
	 */
	public data class Exclude<T : Any>(
		public override val field: SearchableField,
		@JvmField public val values: Set<T>,
	) : QueryCriteria<T> {

		init {
			check(values.all { x -> field.type.isInstance(x) })
		}
	}

	/**
	 * Represents a range criterion that allows search based on a range of values.
	 *
	 * @param T The type of value used in the range (must be comparable).
	 * @property from The starting value of the range (inclusive).
	 * @property to The ending value of the range (inclusive).
	 *
	 * ### Example Usage:
	 * ```kotlin
	 * val yearRange = QueryCriteria.Range(SearchableField.PUBLICATION_YEAR, 2000, 2020)
	 * ```
	 */
	public data class Range<T : Comparable<T>>(
		public override val field: SearchableField,
		@JvmField public val from: T,
		@JvmField public val to: T,
	) : QueryCriteria<T> {

		init {
			check(field.type.isInstance(from))
			check(field.type.isInstance(to))
		}
	}


	/**
	 * Represents a match criterion that search results based on an exact match of a value.
	 *
	 * @param T The type of value being matched.
	 * @property value The exact value that must be matched.
	 *
	 * ### Example Usage:
	 * ```kotlin
	 * val titleMatch = QueryCriteria.Match(SearchableField.TITLE, "manga title")
	 * ```
	 */
	public data class Match<T : Any>(
		public override val field: SearchableField,
		@JvmField public val value: T,
	) : QueryCriteria<T> {

		init {
			check(field.type.isInstance(value))
		}
	}
}

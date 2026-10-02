package org.koitharu.kotatsu.parsers.model.search

import androidx.collection.ArrayMap
import androidx.collection.ArraySet
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Represents a search query for filtering and sorting manga search results.
 * This class is immutable and must be constructed using the [Builder].
 *
 * @property criteria The set of search criteria applied to the query.
 * @property order The sorting order for the results (optional).
 * @property offset The offset number for paginated search results (optional).
 */

@Deprecated("Too complex. Use MangaListFilter instead")
@ConsistentCopyVisibility
public data class MangaSearchQuery private constructor(
	@JvmField public val criteria: Set<QueryCriteria<*>>,
	@JvmField public val order: SortOrder?,
	@JvmField public val offset: Int,
	@JvmField public val skipValidation: Boolean,
) {

	public fun newBuilder(): Builder = Builder(this)

	public class Builder {

		private val criteria = ArraySet<QueryCriteria<*>>()
		private var order: SortOrder? = null
		private var offset: Int = 0
		private var skipValidation: Boolean = false

		public constructor()

		public constructor(query: MangaSearchQuery) : this() {
			criteria.addAll(query.criteria)
			order = query.order
			offset = query.offset
		}

		public fun criterion(criterion: QueryCriteria<*>): Builder = apply { criteria.add(criterion) }

		public fun order(order: SortOrder?): Builder = apply { this.order = order }

		public fun offset(offset: Int): Builder = apply { this.offset = offset }

		public fun skipValidation(skip: Boolean): Builder = apply { this.skipValidation = skip }

		@Throws(IllegalArgumentException::class)
		public fun build(): MangaSearchQuery {
			return MangaSearchQuery(deduplicateCriteria(criteria), order, offset, skipValidation)
		}

		private fun deduplicateCriteria(criteria: Set<QueryCriteria<*>>): Set<QueryCriteria<*>> {
			val uniqueCriteria =
				ArrayMap<Pair<SearchableField, Class<out QueryCriteria<*>>>, QueryCriteria<*>>(criteria.size)

			for (criterion in criteria) {
				val key = criterion.field to criterion::class.java
				val existing = uniqueCriteria[key]

				when {
					existing == null -> uniqueCriteria[key] = criterion

					existing is QueryCriteria.Include<*> && criterion is QueryCriteria.Include<*> -> {
						uniqueCriteria[key] =
							QueryCriteria.Include(criterion.field, existing.values union criterion.values)
					}

					existing is QueryCriteria.Exclude<*> && criterion is QueryCriteria.Exclude<*> -> {
						uniqueCriteria[key] =
							QueryCriteria.Exclude(criterion.field, existing.values union criterion.values)
					}

					else -> throw IllegalArgumentException(
						"Match and Range have only one criterion per type, but found duplicates for: ${criterion.field} in ${criterion::class.simpleName}",
					)
				}
			}

			return uniqueCriteria.values.toSet()
		}
	}

	public companion object {

		public val EMPTY: MangaSearchQuery = MangaSearchQuery(emptySet(), null, 0, false)
	}
}

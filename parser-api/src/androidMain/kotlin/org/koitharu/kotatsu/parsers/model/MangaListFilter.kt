package org.koitharu.kotatsu.parsers.model

import java.util.*

public data class MangaListFilter(
	@JvmField val query: String? = null,
	@JvmField val tags: Set<MangaTag> = emptySet(),
	@JvmField val tagsExclude: Set<MangaTag> = emptySet(),
	@JvmField val locale: Locale? = null,
	@JvmField val originalLocale: Locale? = null,
	@JvmField val states: Set<MangaState> = emptySet(),
	@JvmField val contentRating: Set<ContentRating> = emptySet(),
	@JvmField val types: Set<ContentType> = emptySet(),
	@JvmField val demographics: Set<Demographic> = emptySet(),
	@JvmField val year: Int = YEAR_UNKNOWN,
	@JvmField val yearFrom: Int = YEAR_UNKNOWN,
	@JvmField val yearTo: Int = YEAR_UNKNOWN,
	@JvmField val author: String? = null,
) {

	private fun isNonSearchOptionsEmpty(): Boolean = tags.isEmpty() &&
		tagsExclude.isEmpty() &&
		locale == null &&
		originalLocale == null &&
		states.isEmpty() &&
		contentRating.isEmpty() &&
		year == YEAR_UNKNOWN &&
		yearFrom == YEAR_UNKNOWN &&
		yearTo == YEAR_UNKNOWN &&
		types.isEmpty() &&
		demographics.isEmpty() &&
		author.isNullOrEmpty()

	public fun isEmpty(): Boolean = isNonSearchOptionsEmpty() && query.isNullOrEmpty()

	public fun isNotEmpty(): Boolean = !isEmpty()

	public fun hasNonSearchOptions(): Boolean = !isNonSearchOptionsEmpty()

	public companion object {

		@JvmStatic
		public val EMPTY: MangaListFilter = MangaListFilter()
	}

	internal class Builder {
		private var query: String? = null
		private val tags: MutableSet<MangaTag> = mutableSetOf()
		private val tagsExclude: MutableSet<MangaTag> = mutableSetOf()
		private var locale: Locale? = null
		private var originalLocale: Locale? = null
		private val states: MutableSet<MangaState> = mutableSetOf()
		private val contentRating: MutableSet<ContentRating> = mutableSetOf()
		private val types: MutableSet<ContentType> = mutableSetOf()
		private val demographics: MutableSet<Demographic> = mutableSetOf()
		private var year: Int = YEAR_UNKNOWN
		private var yearFrom: Int = YEAR_UNKNOWN
		private var yearTo: Int = YEAR_UNKNOWN

		fun query(query: String?): Builder = apply { this.query = query }
		fun addTag(tag: MangaTag): Builder = apply { tags.add(tag) }
		fun addTags(tags: Collection<MangaTag>): Builder = apply { this.tags.addAll(tags) }
		fun excludeTag(tag: MangaTag): Builder = apply { tagsExclude.add(tag) }
		fun excludeTags(tags: Collection<MangaTag>): Builder = apply { this.tagsExclude.addAll(tags) }
		fun locale(locale: Locale?): Builder = apply { this.locale = locale }
		fun originalLocale(locale: Locale?): Builder = apply { this.originalLocale = locale }
		fun addState(state: MangaState): Builder = apply { states.add(state) }
		fun addStates(states: Collection<MangaState>): Builder = apply { this.states.addAll(states) }
		fun addContentRating(rating: ContentRating): Builder = apply { contentRating.add(rating) }
		fun addContentRatings(ratings: Collection<ContentRating>): Builder =
			apply { this.contentRating.addAll(ratings) }

		fun addType(type: ContentType): Builder = apply { types.add(type) }
		fun addTypes(types: Collection<ContentType>): Builder = apply { this.types.addAll(types) }
		fun addDemographic(demographic: Demographic): Builder = apply { demographics.add(demographic) }
		fun addDemographics(demographics: Collection<Demographic>): Builder =
			apply { this.demographics.addAll(demographics) }

		fun year(year: Int): Builder = apply { this.year = year }
		fun yearFrom(year: Int): Builder = apply { this.yearFrom = year }
		fun yearTo(year: Int): Builder = apply { this.yearTo = year }

		fun build(): MangaListFilter = MangaListFilter(
			query, tags, tagsExclude, locale, originalLocale, states,
			contentRating, types, demographics, year, yearFrom, yearTo,
		)
	}
}

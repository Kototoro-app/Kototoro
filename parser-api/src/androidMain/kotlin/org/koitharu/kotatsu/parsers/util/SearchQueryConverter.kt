package org.koitharu.kotatsu.parsers.util

import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*

/**
 * Converts a [MangaListFilter] into a [MangaSearchQuery].
 *
 * This function iterates through the filter attributes in [MangaListFilter] and creates corresponding
 * search criteria in a [MangaSearchQuery.Builder].
 *
 * @param filter The [MangaListFilter] to convert.
 * @return A [MangaSearchQuery] constructed based on the given [filter].
 */
internal fun convertToMangaSearchQuery(offset: Int, sortOrder: SortOrder, filter: MangaListFilter): MangaSearchQuery {
	return MangaSearchQuery.Builder().apply {
		offset(offset)
		order(sortOrder)
		if (filter.tags.isNotEmpty()) criterion(Include(TAG, filter.tags))
		if (filter.tagsExclude.isNotEmpty()) criterion(Exclude(TAG, filter.tagsExclude))
		if (filter.states.isNotEmpty()) criterion(Include(STATE, filter.states))
		if (filter.types.isNotEmpty()) criterion(Include(CONTENT_TYPE, filter.types))
		if (filter.contentRating.isNotEmpty()) criterion(Include(CONTENT_RATING, filter.contentRating))
		if (filter.demographics.isNotEmpty()) criterion(Include(DEMOGRAPHIC, filter.demographics))
		if (validateYear(filter.yearFrom) || validateYear(filter.yearTo)) {
			criterion(QueryCriteria.Range(PUBLICATION_YEAR, filter.yearFrom, filter.yearTo))
		}
		if (validateYear(filter.year)) {
			criterion(Match(PUBLICATION_YEAR, filter.year))
		}
		filter.locale?.let {
			criterion(Include(LANGUAGE, setOf(it)))
		}
		filter.originalLocale?.let {
			criterion(Include(ORIGINAL_LANGUAGE, setOf(it)))
		}
		filter.query?.takeIf { it.isNotBlank() }?.let {
			criterion(Match(TITLE_NAME, it))
		}
	}.build()
}

/**
 * Converts a {@link MangaSearchQuery} into a {@link MangaListFilter}.
 * <p>
 * This method iterates through the search criteria defined in the provided {@code searchQuery}
 * and applies them to a {@link MangaListFilter.Builder}. The criteria are processed based on
 * their types, such as inclusion, exclusion, equality checks, range filtering, and pattern matching.
 * </p>
 * <p>
 * Supported criteria:
 * <ul>
 *     <li>{@link QueryCriteria.Include} - Adds tags, states, content types, content ratings, demographics, and languages.</li>
 *     <li>{@link QueryCriteria.Exclude} - Excludes tags.</li>
 *     <li>{@link QueryCriteria.Equals} - Sets specific values like publication year.</li>
 *     <li>{@link QueryCriteria.Between} - Sets a range of values like publication year range.</li>
 *     <li>{@link QueryCriteria.Match} - Adds a search pattern for the title name.</li>
 * </ul>
 * </p>
 * <p>
 * If an unsupported field is encountered, an {@link UnsupportedOperationException} is thrown.
 * </p>
 *
 * @param searchQuery The {@link MangaSearchQuery} to convert.
 * @return A {@link MangaListFilter} constructed based on the given {@code searchQuery}.
 * @throws UnsupportedOperationException If the search criteria contain unsupported fields.
 */
internal fun convertToMangaListFilter(searchQuery: MangaSearchQuery): MangaListFilter {
	return MangaListFilter.Builder().apply {
		for (criterion in searchQuery.criteria) {
			when (criterion) {
				is Include<*> -> handleInclude(this, criterion)
				is Exclude<*> -> handleExclude(this, criterion)
				is Range<*> -> handleBetween(this, criterion)
				is Match<*> -> handleMatch(this, criterion)
			}
		}
	}.build()
}

internal fun MangaSearchQueryCapabilities.toMangaListFilterCapabilities() = MangaListFilterCapabilities(
	isMultipleTagsSupported = capabilities.any { x -> x.field == TAG && x.isMultiple },
	isTagsExclusionSupported = capabilities.any { x -> x.field == TAG && x.criteriaTypes.contains(Exclude::class) },
	isSearchSupported = capabilities.any { x -> x.field == TITLE_NAME },
	isSearchWithFiltersSupported = capabilities.any { x -> x.field == TITLE_NAME && !x.isExclusive },
	isYearSupported = capabilities.any { x -> x.field == PUBLICATION_YEAR && x.criteriaTypes.contains(Match::class) },
	isYearRangeSupported = capabilities.any { x -> x.field == PUBLICATION_YEAR && x.criteriaTypes.contains(Range::class) },
	isOriginalLocaleSupported = capabilities.any { x -> x.field == ORIGINAL_LANGUAGE },
	isAuthorSearchSupported = capabilities.any { x -> x.field == AUTHOR },
)

internal fun MangaListFilterCapabilities.toMangaSearchQueryCapabilities(): MangaSearchQueryCapabilities =
	MangaSearchQueryCapabilities(
		capabilities = setOfNotNull(
			isMultipleTagsSupported.takeIf { it }?.let {
				SearchCapability(
					field = TAG,
					criteriaTypes = setOf(Include::class),
					isMultiple = true,
				)
			},
			isTagsExclusionSupported.takeIf { it }?.let {
				SearchCapability(
					field = TAG,
					criteriaTypes = setOf(Exclude::class),
					isMultiple = true,
				)
			},
			isSearchSupported.takeIf { it }?.let {
				SearchCapability(
					field = TITLE_NAME,
					criteriaTypes = setOf(Match::class),
					isMultiple = false,
					isExclusive = true,
				)
			},
			isSearchWithFiltersSupported.takeIf { it }?.let {
				SearchCapability(
					field = TITLE_NAME,
					criteriaTypes = setOf(Match::class),
					isMultiple = false,
				)
			},
			isYearSupported.takeIf { it }?.let {
				SearchCapability(
					field = PUBLICATION_YEAR,
					criteriaTypes = setOf(Match::class),
					isMultiple = false,
				)
			},
			isYearRangeSupported.takeIf { it }?.let {
				SearchCapability(
					field = PUBLICATION_YEAR,
					criteriaTypes = setOf(Range::class),
					isMultiple = false,
				)
			},
			isOriginalLocaleSupported.takeIf { it }?.let {
				SearchCapability(
					field = ORIGINAL_LANGUAGE,
					criteriaTypes = setOf(Include::class),
					isMultiple = true,
				)
			},
			SearchCapability(
				field = LANGUAGE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = STATE, criteriaTypes = setOf(Include::class), isMultiple = true,
			),
			SearchCapability(
				field = CONTENT_TYPE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = CONTENT_RATING,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = DEMOGRAPHIC,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
		),
	)

private fun handleInclude(builder: MangaListFilter.Builder, criterion: Include<*>) {
	val type = criterion.field.type

	when (criterion.field) {
		TAG -> builder.addTags(filterValues(criterion, type))
		STATE -> builder.addStates(filterValues(criterion, type))
		CONTENT_TYPE -> builder.addTypes(filterValues(criterion, type))
		CONTENT_RATING -> builder.addContentRatings(filterValues(criterion, type))
		DEMOGRAPHIC -> builder.addDemographics(filterValues(criterion, type))
		LANGUAGE -> builder.locale(getFirstValue(criterion, type))
		ORIGINAL_LANGUAGE -> builder.originalLocale(getFirstValue(criterion, type))
		else -> throw IllegalArgumentException("Unsupported field for Include criterion: ${criterion.field}")
	}
}

private fun handleExclude(builder: MangaListFilter.Builder, criterion: Exclude<*>) {
	val type = criterion.field.type

	when (criterion.field) {
		TAG -> builder.excludeTags(filterValues(criterion, type))
		else -> throw IllegalArgumentException("Unsupported field for Exclude criterion: ${criterion.field}")
	}
}

private fun handleBetween(builder: MangaListFilter.Builder, criterion: Range<*>) {
	val type = criterion.field.type

	when (criterion.field) {
		PUBLICATION_YEAR -> {
			builder.yearFrom(getValue(criterion.from, type, YEAR_UNKNOWN))
			builder.yearTo(getValue(criterion.to, type, YEAR_UNKNOWN))
		}

		else -> throw IllegalArgumentException("Unsupported field for Between criterion: ${criterion.field}")
	}
}

private fun handleMatch(builder: MangaListFilter.Builder, criterion: Match<*>) {
	val type = criterion.field.type

	when (criterion.field) {
		TITLE_NAME -> builder.query(getValue(criterion.value, type, ""))
		PUBLICATION_YEAR -> builder.year(getValue(criterion.value, type, YEAR_UNKNOWN))
		else -> throw IllegalArgumentException("Unsupported field for Match criterion: ${criterion.field}")
	}
}

@Suppress("UNCHECKED_CAST")
private fun <T> filterValues(criterion: Include<*>, type: Class<*>): List<T> {
	return criterion.values.filter { type.isInstance(it) } as List<T>
}

@Suppress("UNCHECKED_CAST")
private fun <T> filterValues(criterion: Exclude<*>, type: Class<*>): List<T> {
	return criterion.values.filter { type.isInstance(it) } as List<T>
}

@Suppress("UNCHECKED_CAST")
private fun <T> getFirstValue(criterion: Include<*>, type: Class<*>): T? {
	return criterion.values.firstOrNull { type.isInstance(it) } as? T
}

@Suppress("UNCHECKED_CAST")
private fun <T> getValue(value: Any?, type: Class<*>, default: T): T {
	val isCompatibleIntType = (type == Int::class.java && Integer::class.isInstance(value))

	return if (type.isInstance(value) || isCompatibleIntType) value as T else default
}

private fun validateYear(year: Int) = year != YEAR_UNKNOWN

package org.koitharu.kotatsu.parsers.model

import org.koitharu.kotatsu.parsers.InternalParsersApi

public data class MangaListFilterCapabilities @InternalParsersApi constructor(

	/**
	 * Whether parser supports filtering by more than one tag
	 * @see [MangaListFilter.tags]
	 * @see [MangaListFilterOptions.availableTags]
	 */
	val isMultipleTagsSupported: Boolean = false,

	/**
	 * Whether parser supports tagsExclude field in filter
	 * @see [MangaListFilter.tagsExclude]
	 * @see [MangaListFilterOptions.availableTags]
	 */
	val isTagsExclusionSupported: Boolean = false,

	/**
	 * Whether parser supports searching by string query
	 * @see [MangaListFilter.query]
	 */
	val isSearchSupported: Boolean = false,

	/**
	 * Whether parser supports searching by string query combined within other filters
	 */
	val isSearchWithFiltersSupported: Boolean = false,

	/**
	 * Whether parser supports searching/filtering by year
	 * @see [MangaListFilter.year]
	 */
	val isYearSupported: Boolean = false,

	/**
	 * Whether parser supports searching by year range
	 * @see [MangaListFilter.yearFrom] and [MangaListFilter.yearTo]
	 */
	val isYearRangeSupported: Boolean = false,

	/**
	 * Whether parser supports searching Original Languages
	 * @see [MangaListFilter.originalLocale]
	 * @see [MangaListFilterOptions.availableLocales]
	 */
	val isOriginalLocaleSupported: Boolean = false,

	/**
	 * Whether parser supports searching by author name
	 * @see [MangaListFilter.author]
	 */
	val isAuthorSearchSupported: Boolean = false,
)

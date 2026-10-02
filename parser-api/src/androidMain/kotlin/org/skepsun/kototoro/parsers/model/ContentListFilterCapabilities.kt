package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.InternalParsersApi

public data class ContentListFilterCapabilities @InternalParsersApi constructor(

	/**
	 * Whether parser supports filtering by more than one tag
	 * @see [ContentListFilter.tags]
	 * @see [ContentListFilterOptions.availableTags]
	 */
	val isMultipleTagsSupported: Boolean = false,

	/**
	 * Whether parser supports tagsExclude field in filter
	 * @see [ContentListFilter.tagsExclude]
	 * @see [ContentListFilterOptions.availableTags]
	 */
	val isTagsExclusionSupported: Boolean = false,

	/**
	 * Whether parser supports searching by string query
	 * @see [ContentListFilter.query]
	 */
	val isSearchSupported: Boolean = false,

	/**
	 * Whether parser supports searching by string query combined within other filters
	 */
	val isSearchWithFiltersSupported: Boolean = false,

	/**
	 * Whether parser supports searching/filtering by year
	 * @see [ContentListFilter.year]
	 */
	val isYearSupported: Boolean = false,

	/**
	 * Whether parser supports searching by year range
	 * @see [ContentListFilter.yearFrom] and [ContentListFilter.yearTo]
	 */
	val isYearRangeSupported: Boolean = false,

	/**
	 * Whether parser supports searching Original Languages
	 * @see [ContentListFilter.originalLocale]
	 * @see [ContentListFilterOptions.availableLocales]
	 */
	val isOriginalLocaleSupported: Boolean = false,

	/**
	 * Whether parser supports searching by author name
	 * @see [ContentListFilter.author]
	 */
	val isAuthorSearchSupported: Boolean = false,
)

package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.InternalParsersApi
import java.util.*

public data class ContentListFilterOptions @InternalParsersApi constructor(

	/**
	 * Available tags (genres)
	 */
	public val availableTags: Set<ContentTag> = emptySet(),

	/**
	 * Optional grouped tags for better UI presentation.
	 * Client should prefer groups if not empty, otherwise fallback to [availableTags].
	 */
	public val tagGroups: List<ContentTagGroup> = emptyList(),

	/**
	 * Effective tag groups: use [tagGroups] if provided, otherwise wrap [availableTags] as a single group.
	 */
	public val effectiveTagGroups: List<ContentTagGroup> = when {
		tagGroups.isNotEmpty() -> tagGroups
		availableTags.isNotEmpty() -> listOf(ContentTagGroup("Tags", availableTags))
		else -> emptyList()
	},

	/**
	 * Supported [ContentState] variants for filtering. May be empty.
	 *
	 * For better performance use [EnumSet] for more than one item.
	 */
	public val availableStates: Set<ContentState> = emptySet(),

	/**
	 * Supported [ContentRating] variants for filtering. May be empty.
	 *
	 * For better performance use [EnumSet] for more than one item.
	 */
	public val availableContentRating: Set<ContentRating> = emptySet(),

	/**
	 * Supported [ContentType] variants for filtering. May be empty.
	 *
	 * For better performance use [EnumSet] for more than one item.
	 */
	public val availableContentTypes: Set<ContentType> = emptySet(),

	/**
	 * Supported [Demographic] variants for filtering. May be empty.
	 *
	 * For better performance use [EnumSet] for more than one item.
	 */
	public val availableDemographics: Set<Demographic> = emptySet(),

	/**
	 * Supported content locales for multilingual sources
	 */
	public val availableLocales: Set<Locale> = emptySet(),
)

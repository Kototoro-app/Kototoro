package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/** Data-only AniList ABI used by Cloudstream plugins for JSON mapping. */
class AniListApi : SyncAPI() {
	data class Title(
		val english: String?,
		val romaji: String?,
	)

	data class CoverImage(
		val medium: String?,
		val large: String?,
		val extraLarge: String?,
	)

	data class LikePageInfo(
		val total: Int?,
		val currentPage: Int?,
		val lastPage: Int?,
		val perPage: Int?,
		val hasNextPage: Boolean?,
	)

	data class SeasonNextAiringEpisode(
		val episode: Int?,
		val timeUntilAiring: Int?,
	)

	data class RecommendationConnection(
		val edges: List<RecommendationEdge> = emptyList(),
		val nodes: List<Recommendation> = emptyList(),
	)

	data class RecommendationEdge(
		val node: Recommendation,
	)

	data class Recommendation(
		val id: Long,
		val mediaRecommendation: RecommendedMedia?,
	)

	data class RecommendedMedia(
		val id: Int?,
		val title: MediaTitle?,
		val coverImage: MediaCoverImage?,
	)

	data class MediaTitle(
		val romaji: String?,
		val english: String?,
		val native: String?,
		val userPreferred: String?,
	)

	data class MediaCoverImage(
		val extraLarge: String?,
		val large: String?,
		val medium: String?,
		val color: String?,
	)
}

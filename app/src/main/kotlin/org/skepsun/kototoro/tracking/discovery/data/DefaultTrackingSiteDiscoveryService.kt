
package org.skepsun.kototoro.tracking.discovery.data

import org.skepsun.kototoro.scrobbling.anilist.data.AniListRepository
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiRepository
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuRepository
import org.skepsun.kototoro.scrobbling.mal.data.MALRepository
import org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesRepository
import org.skepsun.kototoro.scrobbling.shikimori.data.ShikimoriRepository
import org.skepsun.kototoro.scrobbling.simkl.data.SimklCatalogItem
import org.skepsun.kototoro.scrobbling.simkl.data.SimklRepository
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerRepositoryMap
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCapabilities
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingEntitySearchResult
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteSortOption
import org.skepsun.kototoro.tracking.discovery.domain.resolveTrackingSeason
import org.skepsun.kototoro.tracking.discovery.domain.trackingCalendarDate
import javax.inject.Inject
import javax.inject.Singleton
import java.time.LocalDate

@Singleton
class DefaultTrackingSiteDiscoveryService @Inject constructor(
	private val repositoryMap: ScrobblerRepositoryMap,
	private val aniListRepository: AniListRepository,
	private val bangumiRepository: BangumiRepository,
	private val kitsuRepository: KitsuRepository,
	private val malRepository: MALRepository,
	private val mangaUpdatesRepository: MangaUpdatesRepository,
	private val shikimoriRepository: ShikimoriRepository,
	private val simklRepository: SimklRepository,
	private val cacheRepository: TrackingSiteCacheRepository,
) : TrackingSiteDiscoveryService {

	private fun siblingCategorySortOption(
		id: String,
		nameResId: Int,
		targetCategoryId: String,
	): TrackingSiteSortOption {
		return TrackingSiteSortOption(
			id = id,
			nameResId = nameResId,
			targetCategoryId = targetCategoryId,
		)
	}

	private fun directSortOption(
		id: String,
		nameResId: Int,
		trackingSortKey: String,
	): TrackingSiteSortOption {
		return TrackingSiteSortOption(
			id = id,
			nameResId = nameResId,
			trackingSortKey = trackingSortKey,
		)
	}

	private fun bangumiRankCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			directSortOption("bangumi_rank", org.skepsun.kototoro.R.string.sort_by_ranking, "rank"),
			directSortOption("bangumi_popularity", org.skepsun.kototoro.R.string.sort_by_popularity_label, "popularity"),
			directSortOption("bangumi_collection", org.skepsun.kototoro.R.string.sort_by_collection, "collection"),
			directSortOption("bangumi_date", org.skepsun.kototoro.R.string.sort_by_date_label, "date"),
			directSortOption("bangumi_name", org.skepsun.kototoro.R.string.sort_by_name_label, "name"),
		)
	}

	private fun kitsuCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			directSortOption("kitsu_popularity", org.skepsun.kototoro.R.string.sort_by_popularity_label, "-userCount"),
			directSortOption("kitsu_rating", org.skepsun.kototoro.R.string.sort_by_ranking, "-averageRating"),
			directSortOption("kitsu_updated", org.skepsun.kototoro.R.string.sort_by_date_label, "-updatedAt"),
			directSortOption("kitsu_name", org.skepsun.kototoro.R.string.sort_by_name_label, "canonicalTitle"),
		)
	}

	private fun malAnimeCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("anime_all", org.skepsun.kototoro.R.string.mal_category_anime_top, "anime_all"),
			siblingCategorySortOption("anime_airing", org.skepsun.kototoro.R.string.mal_category_anime_airing, "anime_airing"),
			siblingCategorySortOption("anime_upcoming", org.skepsun.kototoro.R.string.mal_category_anime_upcoming, "anime_upcoming"),
			siblingCategorySortOption("anime_tv", org.skepsun.kototoro.R.string.mal_category_anime_tv, "anime_tv"),
			siblingCategorySortOption("anime_movie", org.skepsun.kototoro.R.string.mal_category_anime_movie, "anime_movie"),
			siblingCategorySortOption("anime_ova", org.skepsun.kototoro.R.string.mal_category_anime_ova, "anime_ova"),
			siblingCategorySortOption("anime_special", org.skepsun.kototoro.R.string.mal_category_anime_special, "anime_special"),
			siblingCategorySortOption("anime_bypopularity", org.skepsun.kototoro.R.string.mal_category_anime_popular, "anime_bypopularity"),
			siblingCategorySortOption("anime_favorite", org.skepsun.kototoro.R.string.mal_category_anime_favorite, "anime_favorite"),
		)
	}

	private fun malMangaCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("manga_all", org.skepsun.kototoro.R.string.mal_category_manga_top, "manga_all"),
			siblingCategorySortOption("manga_manga", org.skepsun.kototoro.R.string.mal_category_manga_manga, "manga_manga"),
			siblingCategorySortOption("manga_novels", org.skepsun.kototoro.R.string.mal_category_manga_novels, "manga_novels"),
			siblingCategorySortOption("manga_manhwa", org.skepsun.kototoro.R.string.mal_category_manga_manhwa, "manga_manhwa"),
			siblingCategorySortOption("manga_manhua", org.skepsun.kototoro.R.string.mal_category_manga_manhua, "manga_manhua"),
			siblingCategorySortOption("manga_oneshots", org.skepsun.kototoro.R.string.mal_category_manga_oneshots, "manga_oneshots"),
			siblingCategorySortOption("manga_doujin", org.skepsun.kototoro.R.string.mal_category_manga_doujin, "manga_doujin"),
			siblingCategorySortOption("manga_bypopularity", org.skepsun.kototoro.R.string.mal_category_manga_popular, "manga_bypopularity"),
			siblingCategorySortOption("manga_favorite", org.skepsun.kototoro.R.string.mal_category_manga_favorite, "manga_favorite"),
		)
	}

	private fun shikimoriAnimeCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			directSortOption("shiki_ranked", org.skepsun.kototoro.R.string.shiki_category_anime_ranked, "ranked"),
			directSortOption("shiki_popularity", org.skepsun.kototoro.R.string.shiki_category_anime_popular, "popularity"),
			directSortOption("shiki_aired_on", org.skepsun.kototoro.R.string.sort_by_date_label, "aired_on"),
			directSortOption("shiki_name", org.skepsun.kototoro.R.string.sort_by_name_label, "name"),
		)
	}

	private fun shikimoriMangaCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			directSortOption("shiki_ranked", org.skepsun.kototoro.R.string.shiki_category_manga_ranked, "ranked"),
			directSortOption("shiki_popularity", org.skepsun.kototoro.R.string.shiki_category_manga_popular, "popularity"),
			directSortOption("shiki_aired_on", org.skepsun.kototoro.R.string.sort_by_date_label, "aired_on"),
			directSortOption("shiki_name", org.skepsun.kototoro.R.string.sort_by_name_label, "name"),
		)
	}

	private fun simklAnimeCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("simkl_anime_premieres", org.skepsun.kototoro.R.string.simkl_category_anime_premieres, "simkl_anime_premieres"),
			siblingCategorySortOption("simkl_anime_airing", org.skepsun.kototoro.R.string.simkl_category_anime_airing, "simkl_anime_airing"),
			siblingCategorySortOption("simkl_anime_trending", org.skepsun.kototoro.R.string.simkl_category_anime_trending, "simkl_anime_trending"),
			siblingCategorySortOption("simkl_anime_popular", org.skepsun.kototoro.R.string.simkl_category_anime_popular, "simkl_anime_popular"),
		)
	}

	private fun simklTvCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("simkl_tv_premieres", org.skepsun.kototoro.R.string.simkl_category_tv_premieres, "simkl_tv_premieres"),
			siblingCategorySortOption("simkl_tv_airing", org.skepsun.kototoro.R.string.simkl_category_tv_airing, "simkl_tv_airing"),
		)
	}

	private fun simklMovieCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("simkl_movies_trending", org.skepsun.kototoro.R.string.simkl_category_movies_trending, "simkl_movies_trending"),
			siblingCategorySortOption("simkl_movies_popular", org.skepsun.kototoro.R.string.simkl_category_movies_popular, "simkl_movies_popular"),
		)
	}

	private fun aniListAnimeCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("al_anime_trending", org.skepsun.kototoro.R.string.al_category_anime_trending, "al_anime_trending"),
			siblingCategorySortOption("al_anime_popular", org.skepsun.kototoro.R.string.al_category_anime_popular, "al_anime_popular"),
			siblingCategorySortOption("al_anime_score", org.skepsun.kototoro.R.string.al_category_anime_top, "al_anime_score"),
			siblingCategorySortOption("al_anime_upcoming", org.skepsun.kototoro.R.string.al_category_anime_upcoming, "al_anime_upcoming"),
			siblingCategorySortOption("al_anime_movies", org.skepsun.kototoro.R.string.al_category_anime_movies, "al_anime_movies"),
			siblingCategorySortOption("al_anime_series", org.skepsun.kototoro.R.string.al_category_anime_series, "al_anime_series"),
			siblingCategorySortOption("al_anime_favourites", org.skepsun.kototoro.R.string.al_category_anime_favourites, "al_anime_favourites"),
		)
	}

	private fun aniListMangaCategoryOptions(): List<TrackingSiteSortOption> {
		return listOf(
			siblingCategorySortOption("al_manga_trending", org.skepsun.kototoro.R.string.al_category_manga_trending, "al_manga_trending"),
			siblingCategorySortOption("al_manga_popular", org.skepsun.kototoro.R.string.al_category_manga_popular, "al_manga_popular"),
			siblingCategorySortOption("al_manga_score", org.skepsun.kototoro.R.string.al_category_manga_top, "al_manga_score"),
			siblingCategorySortOption("al_manga_favourites", org.skepsun.kototoro.R.string.al_category_manga_favourites, "al_manga_favourites"),
			siblingCategorySortOption("al_manga_manhwa", org.skepsun.kototoro.R.string.al_category_manga_manhwa, "al_manga_manhwa"),
			siblingCategorySortOption("al_manga_novels", org.skepsun.kototoro.R.string.al_category_manga_novels, "al_manga_novels"),
		)
	}

	override fun getCapabilities(service: ScrobblerService): TrackingSiteCapabilities = when (service) {
		ScrobblerService.BANGUMI -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory("calendar", org.skepsun.kototoro.R.string.discover_category_calendar),
				TrackingSiteCategory(
					id = "anime",
					nameResId = org.skepsun.kototoro.R.string.discover_category_anime_rank,
					sortOptions = bangumiRankCategoryOptions(),
					defaultSortOptionId = "bangumi_rank",
				),
				TrackingSiteCategory(
					id = "book",
					nameResId = org.skepsun.kototoro.R.string.discover_category_book_rank,
					sortOptions = bangumiRankCategoryOptions(),
					defaultSortOptionId = "bangumi_rank",
				),
				TrackingSiteCategory(
					"music",
					org.skepsun.kototoro.R.string.discover_category_music_rank,
					sortOptions = bangumiRankCategoryOptions(),
					defaultSortOptionId = "bangumi_rank",
				),
				TrackingSiteCategory(
					"game",
					org.skepsun.kototoro.R.string.discover_category_game_rank,
					sortOptions = bangumiRankCategoryOptions(),
					defaultSortOptionId = "bangumi_rank",
				),
				TrackingSiteCategory(
					"real",
					org.skepsun.kototoro.R.string.discover_category_real_rank,
					sortOptions = bangumiRankCategoryOptions(),
					defaultSortOptionId = "bangumi_rank",
				),
			),
		)

		ScrobblerService.KITSU -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory("calendar", org.skepsun.kototoro.R.string.discover_category_calendar),
				TrackingSiteCategory("trending_anime", org.skepsun.kototoro.R.string.kitsu_category_trending_anime, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("trending_manga", org.skepsun.kototoro.R.string.kitsu_category_trending_manga, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("action", org.skepsun.kototoro.R.string.kitsu_category_action, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("romance", org.skepsun.kototoro.R.string.kitsu_category_romance, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("fantasy", org.skepsun.kototoro.R.string.kitsu_category_fantasy, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("comedy", org.skepsun.kototoro.R.string.kitsu_category_comedy, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("science-fiction", org.skepsun.kototoro.R.string.kitsu_category_sci_fi, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("adventure", org.skepsun.kototoro.R.string.kitsu_category_adventure, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("slice-of-life", org.skepsun.kototoro.R.string.kitsu_category_slice_of_life, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("drama", org.skepsun.kototoro.R.string.kitsu_category_drama, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("ecchi", org.skepsun.kototoro.R.string.kitsu_category_ecchi, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("supernatural", org.skepsun.kototoro.R.string.kitsu_category_supernatural, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("horror", org.skepsun.kototoro.R.string.kitsu_category_horror, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
				TrackingSiteCategory("isekai", org.skepsun.kototoro.R.string.kitsu_category_isekai, sortOptions = kitsuCategoryOptions(), defaultSortOptionId = "kitsu_popularity"),
			),
		)

		ScrobblerService.MAL -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory(
					id = "seasonal",
					nameResId = org.skepsun.kototoro.R.string.mal_category_seasonal,
					sortOptions = listOf(
						directSortOption("mal_seasonal_popularity", org.skepsun.kototoro.R.string.sort_by_popularity_label, "anime_num_list_users"),
						directSortOption("mal_seasonal_score", org.skepsun.kototoro.R.string.sort_by_ranking, "anime_score"),
					),
					defaultSortOptionId = "mal_seasonal_popularity",
				),
				TrackingSiteCategory("anime_all", org.skepsun.kototoro.R.string.mal_category_anime_top, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_all"),
				TrackingSiteCategory("anime_airing", org.skepsun.kototoro.R.string.mal_category_anime_airing, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_airing"),
				TrackingSiteCategory("anime_upcoming", org.skepsun.kototoro.R.string.mal_category_anime_upcoming, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_upcoming"),
				TrackingSiteCategory("anime_tv", org.skepsun.kototoro.R.string.mal_category_anime_tv, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_tv"),
				TrackingSiteCategory("anime_movie", org.skepsun.kototoro.R.string.mal_category_anime_movie, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_movie"),
				TrackingSiteCategory("anime_ova", org.skepsun.kototoro.R.string.mal_category_anime_ova, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_ova"),
				TrackingSiteCategory("anime_special", org.skepsun.kototoro.R.string.mal_category_anime_special, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_special"),
				TrackingSiteCategory("anime_bypopularity", org.skepsun.kototoro.R.string.mal_category_anime_popular, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_bypopularity"),
				TrackingSiteCategory("anime_favorite", org.skepsun.kototoro.R.string.mal_category_anime_favorite, sortOptions = malAnimeCategoryOptions(), defaultSortOptionId = "anime_favorite"),
				TrackingSiteCategory("manga_all", org.skepsun.kototoro.R.string.mal_category_manga_top, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_all"),
				TrackingSiteCategory("manga_manga", org.skepsun.kototoro.R.string.mal_category_manga_manga, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_manga"),
				TrackingSiteCategory("manga_novels", org.skepsun.kototoro.R.string.mal_category_manga_novels, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_novels"),
				TrackingSiteCategory("manga_manhwa", org.skepsun.kototoro.R.string.mal_category_manga_manhwa, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_manhwa"),
				TrackingSiteCategory("manga_manhua", org.skepsun.kototoro.R.string.mal_category_manga_manhua, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_manhua"),
				TrackingSiteCategory("manga_oneshots", org.skepsun.kototoro.R.string.mal_category_manga_oneshots, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_oneshots"),
				TrackingSiteCategory("manga_doujin", org.skepsun.kototoro.R.string.mal_category_manga_doujin, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_doujin"),
				TrackingSiteCategory("manga_bypopularity", org.skepsun.kototoro.R.string.mal_category_manga_popular, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_bypopularity"),
				TrackingSiteCategory("manga_favorite", org.skepsun.kototoro.R.string.mal_category_manga_favorite, sortOptions = malMangaCategoryOptions(), defaultSortOptionId = "manga_favorite"),
			),
		)

		ScrobblerService.MANGAUPDATES -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory(
					id = "calendar",
					nameResId = org.skepsun.kototoro.R.string.mu_category_daily_releases,
				),
				TrackingSiteCategory(
					id = "mu_score",
					nameResId = org.skepsun.kototoro.R.string.mu_category_top_rated,
					sortOptions = listOf(
						siblingCategorySortOption("mu_score", org.skepsun.kototoro.R.string.mu_category_top_rated, "mu_score"),
						siblingCategorySortOption("mu_updated", org.skepsun.kototoro.R.string.mu_category_recently_updated, "mu_updated"),
						siblingCategorySortOption("mu_year", org.skepsun.kototoro.R.string.mu_category_by_year, "mu_year"),
						siblingCategorySortOption("mu_title", org.skepsun.kototoro.R.string.mu_category_alphabetical, "mu_title"),
					),
					defaultSortOptionId = "mu_score",
				),
				TrackingSiteCategory(
					id = "mu_updated",
					nameResId = org.skepsun.kototoro.R.string.mu_category_recently_updated,
					sortOptions = listOf(
						siblingCategorySortOption("mu_score", org.skepsun.kototoro.R.string.mu_category_top_rated, "mu_score"),
						siblingCategorySortOption("mu_updated", org.skepsun.kototoro.R.string.mu_category_recently_updated, "mu_updated"),
						siblingCategorySortOption("mu_year", org.skepsun.kototoro.R.string.mu_category_by_year, "mu_year"),
						siblingCategorySortOption("mu_title", org.skepsun.kototoro.R.string.mu_category_alphabetical, "mu_title"),
					),
					defaultSortOptionId = "mu_updated",
				),
				TrackingSiteCategory(
					id = "mu_year",
					nameResId = org.skepsun.kototoro.R.string.mu_category_by_year,
					sortOptions = listOf(
						siblingCategorySortOption("mu_score", org.skepsun.kototoro.R.string.mu_category_top_rated, "mu_score"),
						siblingCategorySortOption("mu_updated", org.skepsun.kototoro.R.string.mu_category_recently_updated, "mu_updated"),
						siblingCategorySortOption("mu_year", org.skepsun.kototoro.R.string.mu_category_by_year, "mu_year"),
						siblingCategorySortOption("mu_title", org.skepsun.kototoro.R.string.mu_category_alphabetical, "mu_title"),
					),
					defaultSortOptionId = "mu_year",
				),
				TrackingSiteCategory(
					id = "mu_title",
					nameResId = org.skepsun.kototoro.R.string.mu_category_alphabetical,
					sortOptions = listOf(
						siblingCategorySortOption("mu_score", org.skepsun.kototoro.R.string.mu_category_top_rated, "mu_score"),
						siblingCategorySortOption("mu_updated", org.skepsun.kototoro.R.string.mu_category_recently_updated, "mu_updated"),
						siblingCategorySortOption("mu_year", org.skepsun.kototoro.R.string.mu_category_by_year, "mu_year"),
						siblingCategorySortOption("mu_title", org.skepsun.kototoro.R.string.mu_category_alphabetical, "mu_title"),
					),
					defaultSortOptionId = "mu_title",
				),
			),
		)

		ScrobblerService.ANILIST -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory(
					id = "al_anime_airing",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_airing,
				),
				TrackingSiteCategory(
					id = "al_anime_trending",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_trending,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_trending",
				),
				TrackingSiteCategory(
					id = "al_anime_popular",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_popular,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_popular",
				),
				TrackingSiteCategory(
					id = "al_anime_score",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_top,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_score",
				),
				TrackingSiteCategory(
					id = "al_anime_upcoming",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_upcoming,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_upcoming",
				),
				TrackingSiteCategory(
					id = "al_anime_movies",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_movies,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_movies",
				),
				TrackingSiteCategory(
					id = "al_anime_series",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_series,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_series",
				),
				TrackingSiteCategory(
					id = "al_anime_favourites",
					nameResId = org.skepsun.kototoro.R.string.al_category_anime_favourites,
					sortOptions = aniListAnimeCategoryOptions(),
					defaultSortOptionId = "al_anime_favourites",
				),
				TrackingSiteCategory(
					id = "al_manga_trending",
					nameResId = org.skepsun.kototoro.R.string.al_category_manga_trending,
					sortOptions = aniListMangaCategoryOptions(),
					defaultSortOptionId = "al_manga_trending",
				),
				TrackingSiteCategory(
					id = "al_manga_popular",
					nameResId = org.skepsun.kototoro.R.string.al_category_manga_popular,
					sortOptions = aniListMangaCategoryOptions(),
					defaultSortOptionId = "al_manga_popular",
				),
				TrackingSiteCategory(
					id = "al_manga_score",
					nameResId = org.skepsun.kototoro.R.string.al_category_manga_top,
					sortOptions = aniListMangaCategoryOptions(),
					defaultSortOptionId = "al_manga_score",
				),
				TrackingSiteCategory(
					id = "al_manga_favourites",
					nameResId = org.skepsun.kototoro.R.string.al_category_manga_favourites,
					sortOptions = aniListMangaCategoryOptions(),
					defaultSortOptionId = "al_manga_favourites",
				),
				TrackingSiteCategory(
					id = "al_manga_manhwa",
					nameResId = org.skepsun.kototoro.R.string.al_category_manga_manhwa,
					sortOptions = aniListMangaCategoryOptions(),
					defaultSortOptionId = "al_manga_manhwa",
				),
				TrackingSiteCategory(
					id = "al_manga_novels",
					nameResId = org.skepsun.kototoro.R.string.al_category_manga_novels,
					sortOptions = aniListMangaCategoryOptions(),
					defaultSortOptionId = "al_manga_novels",
				),
			),
		)


		ScrobblerService.SHIKIMORI -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory(
					id = "shiki_anime_ranked",
					nameResId = org.skepsun.kototoro.R.string.shiki_category_anime_ranked,
					sortOptions = shikimoriAnimeCategoryOptions(),
					defaultSortOptionId = "shiki_ranked",
				),
				TrackingSiteCategory(
					id = "shiki_anime_popular",
					nameResId = org.skepsun.kototoro.R.string.shiki_category_anime_popular,
					sortOptions = shikimoriAnimeCategoryOptions(),
					defaultSortOptionId = "shiki_popularity",
				),
				TrackingSiteCategory(
					id = "shiki_anime_ongoing",
					nameResId = org.skepsun.kototoro.R.string.shiki_category_anime_ongoing,
					sortOptions = shikimoriAnimeCategoryOptions(),
					defaultSortOptionId = "shiki_ranked",
				),
				TrackingSiteCategory(
					id = "shiki_anime_anons",
					nameResId = org.skepsun.kototoro.R.string.shiki_category_anime_upcoming,
					sortOptions = shikimoriAnimeCategoryOptions(),
					defaultSortOptionId = "shiki_popularity",
				),
				TrackingSiteCategory(
					"shiki_seasonal",
					org.skepsun.kototoro.R.string.shiki_category_seasonal,
					sortOptions = shikimoriAnimeCategoryOptions(),
					defaultSortOptionId = "shiki_popularity",
				),
				TrackingSiteCategory(
					id = "shiki_manga_ranked",
					nameResId = org.skepsun.kototoro.R.string.shiki_category_manga_ranked,
					sortOptions = shikimoriMangaCategoryOptions(),
					defaultSortOptionId = "shiki_ranked",
				),
				TrackingSiteCategory(
					id = "shiki_manga_popular",
					nameResId = org.skepsun.kototoro.R.string.shiki_category_manga_popular,
					sortOptions = shikimoriMangaCategoryOptions(),
					defaultSortOptionId = "shiki_popularity",
				),
			),
		)

		ScrobblerService.SIMKL -> TrackingSiteCapabilities(
			supportsDiscovery = true,
			supportsTrending = true,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
			discoveryCategories = listOf(
				TrackingSiteCategory("simkl_anime_premieres", org.skepsun.kototoro.R.string.simkl_category_anime_premieres, sortOptions = simklAnimeCategoryOptions(), defaultSortOptionId = "simkl_anime_premieres"),
				TrackingSiteCategory("simkl_anime_airing", org.skepsun.kototoro.R.string.simkl_category_anime_airing, sortOptions = simklAnimeCategoryOptions(), defaultSortOptionId = "simkl_anime_airing"),
				TrackingSiteCategory("simkl_anime_trending", org.skepsun.kototoro.R.string.simkl_category_anime_trending, sortOptions = simklAnimeCategoryOptions(), defaultSortOptionId = "simkl_anime_trending"),
				TrackingSiteCategory("simkl_anime_popular", org.skepsun.kototoro.R.string.simkl_category_anime_popular, sortOptions = simklAnimeCategoryOptions(), defaultSortOptionId = "simkl_anime_popular"),
				TrackingSiteCategory("simkl_tv_premieres", org.skepsun.kototoro.R.string.simkl_category_tv_premieres, sortOptions = simklTvCategoryOptions(), defaultSortOptionId = "simkl_tv_premieres"),
				TrackingSiteCategory("simkl_tv_airing", org.skepsun.kototoro.R.string.simkl_category_tv_airing, sortOptions = simklTvCategoryOptions(), defaultSortOptionId = "simkl_tv_airing"),
				TrackingSiteCategory("simkl_movies_trending", org.skepsun.kototoro.R.string.simkl_category_movies_trending, sortOptions = simklMovieCategoryOptions(), defaultSortOptionId = "simkl_movies_trending"),
				TrackingSiteCategory("simkl_movies_popular", org.skepsun.kototoro.R.string.simkl_category_movies_popular, sortOptions = simklMovieCategoryOptions(), defaultSortOptionId = "simkl_movies_popular"),
			),
		)

	}

	override suspend fun getTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		if (catalog.service == ScrobblerService.KITSU) {
			return getKitsuTrending(catalog)
		}
		if (catalog.service == ScrobblerService.MAL) {
			return getMalTrending(catalog)
		}
		if (catalog.service == ScrobblerService.MANGAUPDATES) {
			return getMangaUpdatesTrending(catalog)
		}
		if (catalog.service == ScrobblerService.ANILIST) {
			return getAniListTrending(catalog)
		}
		if (catalog.service == ScrobblerService.SHIKIMORI) {
			return getShikimoriTrending(catalog)
		}
		if (catalog.service == ScrobblerService.SIMKL) {
			return getSimklTrending(catalog)
		}
		if (catalog.service != ScrobblerService.BANGUMI) {
			return emptyList()
		}
		
		val requestCategory = catalog.category ?: "anime"

		if (requestCategory.startsWith("calendar")) {
			// Calendar scrape returns all schedule items at once, so paging is ignored
			if (catalog.page > 0) return emptyList()
			
			val dailyCalendar = bangumiRepository.getDailyCalendar()
			val dayFilter = requestCategory.substringAfter("_", "").toIntOrNull()
			
			val targetDay = dayFilter ?: run {
				val cal = java.util.Calendar.getInstance()
				val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
				if (today == java.util.Calendar.SUNDAY) 7 else today - 1
			}

			return dailyCalendar[targetDay].orEmpty()
				.map { item -> item.toTrackingListItem(ScrobblerService.BANGUMI) }
		}
		
		return bangumiRepository.getRankings(
			category = requestCategory, 
			page = catalog.page + 1,
			sortOrder = catalog.trackingSortKey.toBangumiSortOrder() ?: catalog.sortOrder,
			listFilter = catalog.listFilter
		).map { item -> item.toTrackingListItem(ScrobblerService.BANGUMI) }
	}

	override suspend fun search(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val query = catalog.query?.trim().orEmpty()
		if (query.isEmpty()) {
			return getTrending(catalog)
		}

		val searchAnime = catalog.contentType == null || catalog.contentType in VIDEO_CONTENT_TYPES
		val searchManga = catalog.contentType == null || catalog.contentType !in VIDEO_CONTENT_TYPES

		if (catalog.service == ScrobblerService.KITSU) {
			val offset = catalog.page * 20
			val anime = if (searchAnime) runCatching { kitsuRepository.findAnime(query, offset) }.getOrElse { emptyList() } else emptyList()
			val manga = if (searchManga) runCatching { kitsuRepository.findContent(query, offset) }.getOrElse { emptyList() } else emptyList()
			return (anime + manga)
				.distinctBy { it.id }
				.map { it.toTrackingListItem(ScrobblerService.KITSU) }
		}
		if (catalog.service == ScrobblerService.MAL) {
			val offset = catalog.page * 20
			val anime = if (searchAnime) runCatching { malRepository.searchAnime(query, offset) }.getOrElse { emptyList() } else emptyList()
			val manga = if (searchManga) runCatching { malRepository.findContent(query, offset) }.getOrElse { emptyList() } else emptyList()
			return (anime + manga)
				.distinctBy { it.id }
				.map { it.toTrackingListItem(ScrobblerService.MAL) }
		}
		if (catalog.service == ScrobblerService.MANGAUPDATES) {
			// MangaUpdates only supports manga-type content; skip entirely for VIDEO
			if (!searchManga) return emptyList()
			val offset = catalog.page * 25
			return mangaUpdatesRepository.findContent(query, offset)
				.map { it.toTrackingListItem(ScrobblerService.MANGAUPDATES) }
		}
		if (catalog.service == ScrobblerService.SHIKIMORI) {
			val offset = catalog.page * 10
			val anime = if (searchAnime) runCatching { shikimoriRepository.findAnime(query, offset) }.getOrElse { emptyList() } else emptyList()
			val manga = if (searchManga) runCatching { shikimoriRepository.findContent(query, offset) }.getOrElse { emptyList() } else emptyList()
			return (anime + manga)
				.distinctBy { it.id }
				.map { it.toTrackingListItem(ScrobblerService.SHIKIMORI) }
		}
		// AniList and Bangumi: findContent() accepts isAnime to filter by media type.
		// When contentType is null, search both and merge; otherwise search only the matching type.
		val repo = repositoryMap[catalog.service]
		val offset = catalog.page * 10
		return when {
			catalog.contentType == null -> {
				// No filter: search both anime and manga, merge results
				val anime = runCatching { repo.findContent(query, offset, isAnime = true) }.getOrElse { emptyList() }
				val manga = runCatching { repo.findContent(query, offset, isAnime = false) }.getOrElse { emptyList() }
				(anime + manga)
					.distinctBy { it.id }
					.map { item -> item.toTrackingListItem(catalog.service) }
			}
			searchAnime -> {
				repo.findContent(query, offset, isAnime = true)
					.map { item -> item.toTrackingListItem(catalog.service) }
			}
			else -> {
				repo.findContent(query, offset, isAnime = false)
					.map { item -> item.toTrackingListItem(catalog.service) }
			}
		}
	}

	override suspend fun searchEntities(
		service: ScrobblerService,
		entityType: EntityType,
		query: String,
		page: Int,
	): List<TrackingEntitySearchResult> {
		val trimmedQuery = query.trim()
		if (trimmedQuery.isEmpty()) {
			return emptyList()
		}
		val results = when (service) {
			ScrobblerService.ANILIST -> aniListRepository.searchEntities(entityType, trimmedQuery, page + 1)
			ScrobblerService.BANGUMI -> bangumiRepository.searchEntities(entityType, trimmedQuery, page)
			ScrobblerService.KITSU -> kitsuRepository.searchEntities(entityType, trimmedQuery, page)
			ScrobblerService.MAL -> malRepository.searchEntities(entityType, trimmedQuery)
			ScrobblerService.MANGAUPDATES -> mangaUpdatesRepository.searchEntities(entityType, trimmedQuery, page)
			ScrobblerService.SHIKIMORI -> shikimoriRepository.searchEntities(entityType, trimmedQuery, page)
			else -> emptyList()
		}
		return results.map { item ->
			TrackingEntitySearchResult(
				service = service,
				entityType = entityType,
				remoteId = item.id,
				name = item.name,
				altName = item.altName,
				coverUrl = item.cover,
				url = item.url,
			)
		}
	}

	private companion object {
		private val VIDEO_CONTENT_TYPES = setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
	}

	// ── Kitsu helpers ─────────────────────────

	private suspend fun getKitsuTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "trending_anime"
		val page = catalog.page + 1 // convert 0-based to 1-based
		val sort = catalog.trackingSortKey ?: "-userCount"

		return when {
			category.startsWith("calendar") -> {
				val date = trackingCalendarDate(catalog.calendarDateMillis) ?: LocalDate.now()
				kitsuRepository.getDailySchedule(date = date, page = catalog.page)
					.map { it.toTrackingListItem(ScrobblerService.KITSU) }
			}
			category == "trending_anime" -> {
				if (catalog.trackingSortKey == null) {
					if (catalog.page > 0) return emptyList() // trending endpoint returns all at once
					kitsuRepository.getTrending("anime")
						.map { it.toTrackingListItem(ScrobblerService.KITSU) }
				} else {
					kitsuRepository.getRankings("anime", null, page, sort)
						.map { it.toTrackingListItem(ScrobblerService.KITSU) }
				}
			}
			category == "trending_manga" -> {
				if (catalog.trackingSortKey == null) {
					if (catalog.page > 0) return emptyList()
					kitsuRepository.getTrending("manga")
						.map { it.toTrackingListItem(ScrobblerService.KITSU) }
				} else {
					kitsuRepository.getRankings("manga", null, page, sort)
						.map { it.toTrackingListItem(ScrobblerService.KITSU) }
				}
			}
			else -> {
				// Genre-based categories browse anime by default
				kitsuRepository.getRankings("anime", category, page, sort)
					.map { it.toTrackingListItem(ScrobblerService.KITSU) }
			}
		}
	}

	// ── MAL helpers ─────────────────────────────

	private suspend fun getMalTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "anime_all"
		val limit = 20
		val offset = catalog.page * limit

		// Handle seasonal anime separately
		if (category == "seasonal") {
			val date = trackingCalendarDate(catalog.calendarDateMillis) ?: LocalDate.now()
			val season = resolveTrackingSeason(date)
			return malRepository.getSeasonalAnime(
				year = season.year,
				season = season.malSeason,
				sort = catalog.trackingSortKey ?: "anime_num_list_users",
				limit = limit,
				offset = offset,
			)
				.map { it.toTrackingListItem(ScrobblerService.MAL) }
		}

		// Category format: "{mediaType}_{rankingType}", e.g. "anime_all", "manga_bypopularity"
		val mediaType = category.substringBefore("_")
		val rankingType = category.substringAfter("_")

		val items = when (mediaType) {
			"manga" -> malRepository.getMangaRanking(rankingType, limit, offset)
			else -> malRepository.getAnimeRanking(rankingType, limit, offset)
		}
		return items.map { it.toTrackingListItem(ScrobblerService.MAL) }
	}

	// ── MangaUpdates helpers ────────────────────

	private suspend fun getMangaUpdatesTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "mu_score"
		val page = catalog.page + 1 // convert 0-based to 1-based
		if (category.startsWith("calendar")) {
			val date = trackingCalendarDate(catalog.calendarDateMillis) ?: LocalDate.now()
			return mangaUpdatesRepository.getDailyReleases(date = date, page = page)
				.map { it.toTrackingListItem(ScrobblerService.MANGAUPDATES) }
		}

		val orderby = catalog.trackingSortKey ?: when {
			category.contains("score") -> "score"
			category.contains("updated") -> "updated"
			category.contains("year") -> "year"
			category.contains("title") -> "title"
			else -> "score"
		}

		return mangaUpdatesRepository.getRankings(orderby, page)
			.map { it.toTrackingListItem(ScrobblerService.MANGAUPDATES) }
	}

	// ── AniList helpers ────────────────────

	private suspend fun getAniListTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "al_anime_trending"
		val page = catalog.page + 1
		if (category == "al_anime_airing") {
			val dateMillis = catalog.calendarDateMillis ?: System.currentTimeMillis()
			return aniListRepository.getAiringSchedule(dateMillis = dateMillis, page = page)
				.map { it.toTrackingListItem(ScrobblerService.ANILIST) }
		}

		val mediaType = if (category.contains("manga")) "MANGA" else "ANIME"
		val sort = catalog.trackingSortKey ?: when {
			category.contains("trending") -> "TRENDING_DESC"
			category.contains("popular") -> "POPULARITY_DESC"
			category.contains("score") -> "SCORE_DESC"
			category.contains("upcoming") -> "START_DATE_DESC"
			category.contains("favourites") -> "FAVOURITES_DESC"
			category == "al_anime_movies" -> "POPULARITY_DESC"
			category == "al_anime_series" -> "SCORE_DESC"
			category == "al_manga_manhwa" -> "POPULARITY_DESC"
			category == "al_manga_novels" -> "POPULARITY_DESC"
			else -> "TRENDING_DESC"
		}
		val format = when (category) {
			"al_anime_movies" -> "MOVIE"
			"al_anime_series", "al_anime_favourites" -> "TV"
			"al_manga_novels" -> "NOVEL"
			else -> null
		}
		val countryOfOrigin = when (category) {
			"al_manga_manhwa" -> "KR"
			"al_manga_novels" -> "JP"
			else -> null
		}

		return aniListRepository.getTrending(
			mediaType = mediaType,
			sort = sort,
			page = page,
			format = format,
			countryOfOrigin = countryOfOrigin,
		)
			.map { it.toTrackingListItem(ScrobblerService.ANILIST) }
	}

	override suspend fun getDetails(service: ScrobblerService, remoteId: Long, urlHint: String?): TrackingSiteItemDetails {
		if (service == ScrobblerService.MAL) {
			return getMalDetails(remoteId, urlHint)
		}
		if (service == ScrobblerService.KITSU) {
			return getKitsuDetails(remoteId, urlHint)
		}
		if (service == ScrobblerService.SHIKIMORI) {
			return getShikimoriDetails(remoteId, urlHint)
		}
		val info = repositoryMap[service].getContentInfo(remoteId)
		val cachedUrl = urlHint ?: runCatching { cacheRepository.readDetails(service, remoteId)?.url }.getOrNull()
		val resolvedUrl = info.url ?: cachedUrl

		val contentType = when {
			service == ScrobblerService.MANGAUPDATES -> org.skepsun.kototoro.parsers.model.ContentType.MANGA
			service == ScrobblerService.ANILIST && resolvedUrl?.contains("/anime/") == true -> org.skepsun.kototoro.parsers.model.ContentType.VIDEO
			service == ScrobblerService.ANILIST && resolvedUrl?.contains("/manga/") == true -> org.skepsun.kototoro.parsers.model.ContentType.MANGA
			else -> null
		}

		return info.toTrackingDetails(
			service = service,
			contentType = contentType,
		)
	}

	override suspend fun getEntityDetails(
		service: ScrobblerService,
		entityType: EntityType,
		remoteId: Long,
		urlHint: String?,
	): TrackingSiteItemDetails? {
		cacheRepository.readEntityDetails(service, entityType, remoteId)?.let { cached ->
			if (cached.hasEntityRichMetadata()) {
				return cached
			}
		}
		val resolved = when (service) {
			ScrobblerService.ANILIST -> aniListRepository.getEntityInfo(entityType, remoteId)
			ScrobblerService.BANGUMI -> bangumiRepository.getEntityInfo(entityType, remoteId)
			ScrobblerService.KITSU -> kitsuRepository.getEntityInfo(entityType, remoteId)
			ScrobblerService.MAL -> malRepository.getEntityInfo(entityType, remoteId)
			ScrobblerService.MANGAUPDATES -> mangaUpdatesRepository.getEntityInfo(entityType, remoteId, urlHint)
			ScrobblerService.SHIKIMORI -> shikimoriRepository.getEntityInfo(entityType, remoteId)
			else -> null
		}?.toTrackingDetails(service = service, contentType = null)
			?.let { details ->
				details.copy(url = urlHint ?: details.url)
			}
		if (resolved != null) {
			cacheRepository.saveEntityDetails(service, entityType, resolved)
		}
		return resolved
	}

	private suspend fun getMalDetails(remoteId: Long, urlHint: String?): TrackingSiteItemDetails {
		// Use URL hint from the intent, or fall back to cached URL
		val url = urlHint ?: runCatching {
			cacheRepository.readDetails(ScrobblerService.MAL, remoteId)?.url
		}.getOrNull()

		val isAnime = url?.contains("/anime/") == true

		val info = if (isAnime) {
			malRepository.getAnimeInfo(remoteId)
		} else {
			malRepository.getContentInfo(remoteId)
		}
		return info.toTrackingDetails(
			service = ScrobblerService.MAL,
			contentType = if (isAnime) org.skepsun.kototoro.parsers.model.ContentType.VIDEO else org.skepsun.kototoro.parsers.model.ContentType.MANGA,
		)
	}

	private suspend fun getKitsuDetails(remoteId: Long, urlHint: String?): TrackingSiteItemDetails {
		val url = urlHint ?: runCatching {
			cacheRepository.readDetails(ScrobblerService.KITSU, remoteId)?.url
		}.getOrNull()

		val isAnime = url?.contains("/anime/") == true

		val info = if (isAnime) {
			kitsuRepository.getAnimeInfo(remoteId)
		} else {
			kitsuRepository.getContentInfo(remoteId)
		}
		return info.toTrackingDetails(
			service = ScrobblerService.KITSU,
			contentType = if (isAnime) org.skepsun.kototoro.parsers.model.ContentType.VIDEO else org.skepsun.kototoro.parsers.model.ContentType.MANGA,
		)
	}

	// ── Shikimori helpers ────────────────────

	private suspend fun getShikimoriTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "shiki_anime_ranked"
		val page = catalog.page + 1
		val limit = 20
		val order = catalog.trackingSortKey

		if (category == "shiki_seasonal") {
			val date = trackingCalendarDate(catalog.calendarDateMillis) ?: LocalDate.now()
			val season = resolveTrackingSeason(date)
			return shikimoriRepository.getAnimeList(
				order = order ?: "popularity",
				season = season.shikimoriSeason,
				page = page,
				limit = limit,
			).map { it.toTrackingListItem(ScrobblerService.SHIKIMORI) }
		}

		return when (category) {
			"shiki_anime_ranked" -> shikimoriRepository.getAnimeList(order = order ?: "ranked", page = page, limit = limit)
			"shiki_anime_popular" -> shikimoriRepository.getAnimeList(order = order ?: "popularity", page = page, limit = limit)
			"shiki_anime_ongoing" -> shikimoriRepository.getAnimeList(order = order ?: "ranked", status = "ongoing", page = page, limit = limit)
			"shiki_anime_anons" -> shikimoriRepository.getAnimeList(order = order ?: "popularity", status = "anons", page = page, limit = limit)
			"shiki_manga_ranked" -> shikimoriRepository.getMangaList(order = order ?: "ranked", page = page, limit = limit)
			"shiki_manga_popular" -> shikimoriRepository.getMangaList(order = order ?: "popularity", page = page, limit = limit)
			else -> emptyList()
		}.map { it.toTrackingListItem(ScrobblerService.SHIKIMORI) }
	}

	private suspend fun getShikimoriDetails(remoteId: Long, urlHint: String?): TrackingSiteItemDetails {
		val url = urlHint ?: runCatching {
			cacheRepository.readDetails(ScrobblerService.SHIKIMORI, remoteId)?.url
		}.getOrNull()

		val isAnime = url?.contains("/animes/") == true

		val info = if (isAnime) {
			shikimoriRepository.getAnimeInfo(remoteId)
		} else {
			shikimoriRepository.getContentInfo(remoteId)
		}
		return info.toTrackingDetails(
			service = ScrobblerService.SHIKIMORI,
			contentType = if (isAnime) org.skepsun.kototoro.parsers.model.ContentType.VIDEO else org.skepsun.kototoro.parsers.model.ContentType.MANGA,
		)
	}

	private suspend fun getSimklTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "simkl_anime_trending"
		return simklRepository.getDiscoveryItems(
			categoryId = category,
			page = catalog.page,
			calendarDateMillis = catalog.calendarDateMillis,
		)
			.map { it.toTrackingListItem(ScrobblerService.SIMKL) }
	}

	private fun String?.toBangumiSortOrder(): org.skepsun.kototoro.parsers.model.SortOrder? {
		return when (this) {
			"rank" -> org.skepsun.kototoro.parsers.model.SortOrder.RATING
			"popularity" -> org.skepsun.kototoro.parsers.model.SortOrder.POPULARITY
			"collection" -> org.skepsun.kototoro.parsers.model.SortOrder.ADDED
			"date" -> org.skepsun.kototoro.parsers.model.SortOrder.NEWEST
			"name" -> org.skepsun.kototoro.parsers.model.SortOrder.ALPHABETICAL
			else -> null
		}
	}

	private fun ScrobblerContent.toTrackingListItem(service: ScrobblerService): TrackingSiteItem {
		val fallbackTitlePair = when (service) {
			ScrobblerService.BANGUMI -> (altName ?: name) to name.takeIf { altName != null && altName != name }
			ScrobblerService.ANILIST,
			ScrobblerService.SHIKIMORI,
			-> (altName ?: name) to name.takeIf { altName != null && altName != name }
			else -> name to altName
		}
		return TrackingSiteItem(
			service = service,
			remoteId = id,
			title = name,
			altTitle = altName,
			primaryTitle = primaryTitle ?: fallbackTitlePair.first,
			secondaryTitle = secondaryTitle ?: fallbackTitlePair.second,
			progressText = progressText,
			updatedAtText = updatedAtText,
			coverUrl = cover,
			subtitle = subtitle,
			score = score,
			scoreMax = scoreMax,
			url = url,
				totalEpisodes = totalEpisodes,
		)
	}

	private fun SimklCatalogItem.toTrackingListItem(service: ScrobblerService): TrackingSiteItem {
		return TrackingSiteItem(
			service = service,
			remoteId = remoteId,
			title = title,
			altTitle = altTitle,
			primaryTitle = title,
			secondaryTitle = altTitle,
			coverUrl = coverUrl,
			subtitle = subtitle,
			score = score,
			scoreMax = 10f,
			url = url,
		)
	}

	private fun ScrobblerContentInfo.toTrackingDetails(service: ScrobblerService, contentType: org.skepsun.kototoro.parsers.model.ContentType? = null): TrackingSiteItemDetails {
		return TrackingSiteItemDetails(
			service = service,
			remoteId = id,
			title = name,
			coverUrl = cover,
			contentType = contentType ?: this.contentType,
			description = descriptionHtml,
			score = score,
			rank = rank,
			tags = tags,
			authors = authors,
			staff = staff.map { person ->
				TrackingSiteItemDetails.PersonInfo(
					id = person.id,
					name = person.name,
					avatarUrl = person.avatarUrl,
					url = person.url,
					role = person.role,
				)
			},
			totalEpisodes = totalEpisodes,
			url = url,
			infoboxProperties = infoboxProperties,
			episodes = episodes.map { ep ->
				TrackingSiteItemDetails.EpisodeInfo(
					number = ep.number,
					title = ep.title,
					url = ep.url,
				)
			},
			characters = characters.map { character ->
				TrackingSiteItemDetails.CharacterInfo(
					id = character.id,
					name = character.name,
					coverUrl = character.coverUrl,
					role = character.role,
					url = character.url,
					voiceActors = character.voiceActors.map { actor ->
						TrackingSiteItemDetails.PersonInfo(
							id = actor.id,
							name = actor.name,
							avatarUrl = actor.avatarUrl,
							url = actor.url,
							role = actor.role,
						)
					},
				)
			},
			commentThreads = commentThreads.map { thread ->
				TrackingSiteItemDetails.CommentThread(
					id = thread.id,
					userName = thread.userName,
					userUrl = thread.userUrl,
					avatarUrl = thread.avatarUrl,
					rating = thread.rating,
					status = thread.status,
					postedAt = thread.postedAt,
					content = thread.content,
					replies = thread.replies.map { reply ->
						TrackingSiteItemDetails.CommentReply(
							id = reply.id,
							userName = reply.userName,
							userUrl = reply.userUrl,
							avatarUrl = reply.avatarUrl,
							postedAt = reply.postedAt,
							content = reply.content,
						)
					},
				)
			},
			reviews = reviews.map { review ->
				TrackingSiteItemDetails.ReviewEntry(
					id = review.id,
					title = review.title,
					authorName = review.authorName,
					authorUrl = review.authorUrl,
					avatarUrl = review.avatarUrl,
					postedAt = review.postedAt,
					excerpt = review.excerpt,
					url = review.url,
					repliesCount = review.repliesCount,
				)
			},
			relatedWorks = relatedWorks.map { rw ->
				TrackingSiteItemDetails.RelatedWork(
					id = rw.id,
					title = rw.title,
					coverUrl = rw.coverUrl,
					relationship = rw.relationship,
					url = rw.url,
				)
			},
			recommendations = recommendations.map { rec ->
				TrackingSiteItemDetails.RelatedWork(
					id = rec.id,
					title = rec.title,
					coverUrl = rec.coverUrl,
					url = rec.url,
				)
			},
			extraSections = extraSections.map { section ->
				TrackingSiteItemDetails.RelatedSection(
					title = section.title,
					items = section.items.map { item ->
						TrackingSiteItemDetails.RelatedWork(
							id = item.id,
							title = item.title,
							coverUrl = item.coverUrl,
							relationship = item.relationship,
							url = item.url,
						)
					},
				)
			},
			actions = actions.map { action ->
				TrackingSiteItemDetails.ExternalAction(
					title = action.title,
					url = action.url,
				)
			},
		)
	}

	private fun TrackingSiteItemDetails.hasEntityRichMetadata(): Boolean {
		return coverUrl != null ||
			description != null ||
			infoboxProperties.isNotEmpty() ||
			relatedWorks.isNotEmpty() ||
			extraSections.isNotEmpty() ||
			actions.isNotEmpty()
	}
}

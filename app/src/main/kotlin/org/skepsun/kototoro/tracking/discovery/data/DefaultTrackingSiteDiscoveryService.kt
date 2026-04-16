
package org.skepsun.kototoro.tracking.discovery.data

import org.skepsun.kototoro.scrobbling.anilist.data.AniListRepository
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiRepository
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuRepository
import org.skepsun.kototoro.scrobbling.mal.data.MALRepository
import org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesRepository
import org.skepsun.kototoro.scrobbling.shikimori.data.ShikimoriRepository
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerRepositoryMap
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContentInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCapabilities
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTrackingSiteDiscoveryService @Inject constructor(
	private val repositoryMap: ScrobblerRepositoryMap,
	private val aniListRepository: AniListRepository,
	private val bangumiRepository: BangumiRepository,
	private val kitsuRepository: KitsuRepository,
	private val malRepository: MALRepository,
	private val mangaUpdatesRepository: MangaUpdatesRepository,
	private val shikimoriRepository: ShikimoriRepository,
	private val cacheRepository: TrackingSiteCacheRepository,
) : TrackingSiteDiscoveryService {

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
				TrackingSiteCategory("anime", org.skepsun.kototoro.R.string.discover_category_anime_rank),
				TrackingSiteCategory("book", org.skepsun.kototoro.R.string.discover_category_book_rank),
				TrackingSiteCategory("music", org.skepsun.kototoro.R.string.discover_category_music_rank),
				TrackingSiteCategory("game", org.skepsun.kototoro.R.string.discover_category_game_rank),
				TrackingSiteCategory("real", org.skepsun.kototoro.R.string.discover_category_real_rank),
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
				TrackingSiteCategory("trending_anime", org.skepsun.kototoro.R.string.kitsu_category_trending_anime),
				TrackingSiteCategory("trending_manga", org.skepsun.kototoro.R.string.kitsu_category_trending_manga),
				TrackingSiteCategory("action", org.skepsun.kototoro.R.string.kitsu_category_action),
				TrackingSiteCategory("romance", org.skepsun.kototoro.R.string.kitsu_category_romance),
				TrackingSiteCategory("fantasy", org.skepsun.kototoro.R.string.kitsu_category_fantasy),
				TrackingSiteCategory("comedy", org.skepsun.kototoro.R.string.kitsu_category_comedy),
				TrackingSiteCategory("science-fiction", org.skepsun.kototoro.R.string.kitsu_category_sci_fi),
				TrackingSiteCategory("adventure", org.skepsun.kototoro.R.string.kitsu_category_adventure),
				TrackingSiteCategory("slice-of-life", org.skepsun.kototoro.R.string.kitsu_category_slice_of_life),
				TrackingSiteCategory("drama", org.skepsun.kototoro.R.string.kitsu_category_drama),
				TrackingSiteCategory("ecchi", org.skepsun.kototoro.R.string.kitsu_category_ecchi),
				TrackingSiteCategory("supernatural", org.skepsun.kototoro.R.string.kitsu_category_supernatural),
				TrackingSiteCategory("horror", org.skepsun.kototoro.R.string.kitsu_category_horror),
				TrackingSiteCategory("isekai", org.skepsun.kototoro.R.string.kitsu_category_isekai),
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
				TrackingSiteCategory("seasonal", org.skepsun.kototoro.R.string.mal_category_seasonal),
				TrackingSiteCategory("anime_all", org.skepsun.kototoro.R.string.mal_category_anime_top),
				TrackingSiteCategory("anime_airing", org.skepsun.kototoro.R.string.mal_category_anime_airing),
				TrackingSiteCategory("anime_upcoming", org.skepsun.kototoro.R.string.mal_category_anime_upcoming),
				TrackingSiteCategory("anime_tv", org.skepsun.kototoro.R.string.mal_category_anime_tv),
				TrackingSiteCategory("anime_movie", org.skepsun.kototoro.R.string.mal_category_anime_movie),
				TrackingSiteCategory("anime_ova", org.skepsun.kototoro.R.string.mal_category_anime_ova),
				TrackingSiteCategory("anime_special", org.skepsun.kototoro.R.string.mal_category_anime_special),
				TrackingSiteCategory("anime_bypopularity", org.skepsun.kototoro.R.string.mal_category_anime_popular),
				TrackingSiteCategory("anime_favorite", org.skepsun.kototoro.R.string.mal_category_anime_favorite),
				TrackingSiteCategory("manga_all", org.skepsun.kototoro.R.string.mal_category_manga_top),
				TrackingSiteCategory("manga_manga", org.skepsun.kototoro.R.string.mal_category_manga_manga),
				TrackingSiteCategory("manga_novels", org.skepsun.kototoro.R.string.mal_category_manga_novels),
				TrackingSiteCategory("manga_manhwa", org.skepsun.kototoro.R.string.mal_category_manga_manhwa),
				TrackingSiteCategory("manga_manhua", org.skepsun.kototoro.R.string.mal_category_manga_manhua),
				TrackingSiteCategory("manga_oneshots", org.skepsun.kototoro.R.string.mal_category_manga_oneshots),
				TrackingSiteCategory("manga_doujin", org.skepsun.kototoro.R.string.mal_category_manga_doujin),
				TrackingSiteCategory("manga_bypopularity", org.skepsun.kototoro.R.string.mal_category_manga_popular),
				TrackingSiteCategory("manga_favorite", org.skepsun.kototoro.R.string.mal_category_manga_favorite),
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
				TrackingSiteCategory("mu_score", org.skepsun.kototoro.R.string.mu_category_top_rated),
				TrackingSiteCategory("mu_updated", org.skepsun.kototoro.R.string.mu_category_recently_updated),
				TrackingSiteCategory("mu_year", org.skepsun.kototoro.R.string.mu_category_by_year),
				TrackingSiteCategory("mu_title", org.skepsun.kototoro.R.string.mu_category_alphabetical),
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
				TrackingSiteCategory("al_anime_trending", org.skepsun.kototoro.R.string.al_category_anime_trending),
				TrackingSiteCategory("al_anime_popular", org.skepsun.kototoro.R.string.al_category_anime_popular),
				TrackingSiteCategory("al_anime_score", org.skepsun.kototoro.R.string.al_category_anime_top),
				TrackingSiteCategory("al_anime_upcoming", org.skepsun.kototoro.R.string.al_category_anime_upcoming),
				TrackingSiteCategory("al_manga_trending", org.skepsun.kototoro.R.string.al_category_manga_trending),
				TrackingSiteCategory("al_manga_popular", org.skepsun.kototoro.R.string.al_category_manga_popular),
				TrackingSiteCategory("al_manga_score", org.skepsun.kototoro.R.string.al_category_manga_top),
				TrackingSiteCategory("al_manga_favourites", org.skepsun.kototoro.R.string.al_category_manga_favourites),
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
				TrackingSiteCategory("shiki_anime_ranked", org.skepsun.kototoro.R.string.shiki_category_anime_ranked),
				TrackingSiteCategory("shiki_anime_popular", org.skepsun.kototoro.R.string.shiki_category_anime_popular),
				TrackingSiteCategory("shiki_anime_ongoing", org.skepsun.kototoro.R.string.shiki_category_anime_ongoing),
				TrackingSiteCategory("shiki_anime_anons", org.skepsun.kototoro.R.string.shiki_category_anime_upcoming),
				TrackingSiteCategory("shiki_seasonal", org.skepsun.kototoro.R.string.shiki_category_seasonal),
				TrackingSiteCategory("shiki_manga_ranked", org.skepsun.kototoro.R.string.shiki_category_manga_ranked),
				TrackingSiteCategory("shiki_manga_popular", org.skepsun.kototoro.R.string.shiki_category_manga_popular),
			),
		)

		else -> TrackingSiteCapabilities(
			supportsDiscovery = false,
			supportsTrending = false,
			supportsSearch = true,
			supportsDetails = true,
			supportsStatusSync = true,
			supportsManualBinding = true,
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
			sortOrder = catalog.sortOrder,
			listFilter = catalog.listFilter
		).map { item -> item.toTrackingListItem(ScrobblerService.BANGUMI) }
	}

	override suspend fun search(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val query = catalog.query?.trim().orEmpty()
		if (query.isEmpty()) {
			return getTrending(catalog)
		}
		if (catalog.service == ScrobblerService.KITSU) {
			// Search both anime and manga, merge results
			val offset = catalog.page * 20
			val anime = runCatching { kitsuRepository.findAnime(query, offset) }.getOrElse { emptyList() }
			val manga = runCatching { kitsuRepository.findContent(query, offset) }.getOrElse { emptyList() }
			return (anime + manga)
				.distinctBy { it.id }
				.map { it.toTrackingListItem(ScrobblerService.KITSU) }
		}
		if (catalog.service == ScrobblerService.MAL) {
			val offset = catalog.page * 20
			val anime = runCatching { malRepository.searchAnime(query, offset) }.getOrElse { emptyList() }
			val manga = runCatching { malRepository.findContent(query, offset) }.getOrElse { emptyList() }
			return (anime + manga)
				.distinctBy { it.id }
				.map { it.toTrackingListItem(ScrobblerService.MAL) }
		}
		if (catalog.service == ScrobblerService.MANGAUPDATES) {
			val offset = catalog.page * 25
			return mangaUpdatesRepository.findContent(query, offset)
				.map { it.toTrackingListItem(ScrobblerService.MANGAUPDATES) }
		}
		if (catalog.service == ScrobblerService.SHIKIMORI) {
			val offset = catalog.page * 10
			val anime = runCatching { shikimoriRepository.findAnime(query, offset) }.getOrElse { emptyList() }
			val manga = runCatching { shikimoriRepository.findContent(query, offset) }.getOrElse { emptyList() }
			return (anime + manga)
				.distinctBy { it.id }
				.map { it.toTrackingListItem(ScrobblerService.SHIKIMORI) }
		}
		return repositoryMap[catalog.service]
			.findContent(query, catalog.page * 10)
			.map { item -> item.toTrackingListItem(catalog.service) }
	}

	// ── Kitsu helpers ─────────────────────────

	private suspend fun getKitsuTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "trending_anime"
		val page = catalog.page + 1 // convert 0-based to 1-based

		return when {
			category == "trending_anime" -> {
				if (catalog.page > 0) return emptyList() // trending endpoint returns all at once
				kitsuRepository.getTrending("anime")
					.map { it.toTrackingListItem(ScrobblerService.KITSU) }
			}
			category == "trending_manga" -> {
				if (catalog.page > 0) return emptyList()
				kitsuRepository.getTrending("manga")
					.map { it.toTrackingListItem(ScrobblerService.KITSU) }
			}
			else -> {
				// Genre-based categories browse anime by default
				kitsuRepository.getRankings("anime", category, page)
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
			val cal = java.util.Calendar.getInstance()
			val year = cal.get(java.util.Calendar.YEAR)
			val month = cal.get(java.util.Calendar.MONTH) // 0-based
			val season = when (month) {
				in 0..2 -> "winter"
				in 3..5 -> "spring"
				in 6..8 -> "summer"
				else -> "fall"
			}
			return malRepository.getSeasonalAnime(year, season, limit = limit, offset = offset)
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

		val orderby = when {
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

		val mediaType = if (category.contains("manga")) "MANGA" else "ANIME"
		val sort = when {
			category.contains("trending") -> "TRENDING_DESC"
			category.contains("popular") -> "POPULARITY_DESC"
			category.contains("score") -> "SCORE_DESC"
			category.contains("upcoming") -> "START_DATE_DESC"
			category.contains("favourites") -> "FAVOURITES_DESC"
			else -> "TRENDING_DESC"
		}

		return aniListRepository.getTrending(mediaType, sort, page)
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
		return repositoryMap[service]
			.getContentInfo(remoteId)
			.toTrackingDetails(service)
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
		return info.toTrackingDetails(ScrobblerService.MAL)
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
		return info.toTrackingDetails(ScrobblerService.KITSU)
	}

	// ── Shikimori helpers ────────────────────

	private suspend fun getShikimoriTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem> {
		val category = catalog.category ?: "shiki_anime_ranked"
		val page = catalog.page + 1
		val limit = 20

		if (category == "shiki_seasonal") {
			val cal = java.util.Calendar.getInstance()
			val year = cal.get(java.util.Calendar.YEAR)
			val month = cal.get(java.util.Calendar.MONTH) // 0-based
			val season = when (month) {
				in 0..2 -> "winter"
				in 3..5 -> "spring"
				in 6..8 -> "summer"
				else -> "fall"
			}
			return shikimoriRepository.getAnimeList(
				order = "popularity",
				season = "${season}_${year}",
				page = page,
				limit = limit,
			).map { it.toTrackingListItem(ScrobblerService.SHIKIMORI) }
		}

		return when (category) {
			"shiki_anime_ranked" -> shikimoriRepository.getAnimeList(order = "ranked", page = page, limit = limit)
			"shiki_anime_popular" -> shikimoriRepository.getAnimeList(order = "popularity", page = page, limit = limit)
			"shiki_anime_ongoing" -> shikimoriRepository.getAnimeList(order = "ranked", status = "ongoing", page = page, limit = limit)
			"shiki_anime_anons" -> shikimoriRepository.getAnimeList(order = "popularity", status = "anons", page = page, limit = limit)
			"shiki_manga_ranked" -> shikimoriRepository.getMangaList(order = "ranked", page = page, limit = limit)
			"shiki_manga_popular" -> shikimoriRepository.getMangaList(order = "popularity", page = page, limit = limit)
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
		return info.toTrackingDetails(ScrobblerService.SHIKIMORI)
	}

	private fun ScrobblerContent.toTrackingListItem(service: ScrobblerService): TrackingSiteItem {
		return TrackingSiteItem(
			service = service,
			remoteId = id,
			title = name,
			altTitle = altName,
			coverUrl = cover,
			url = url,
		)
	}

	private fun ScrobblerContentInfo.toTrackingDetails(service: ScrobblerService): TrackingSiteItemDetails {
		return TrackingSiteItemDetails(
			service = service,
			remoteId = id,
			title = name,
			altTitles = altTitles,
			externalLinks = externalLinks,
			coverUrl = cover,
			description = descriptionHtml,
			tags = tags,
			authors = authors,
			url = url,
			infoboxProperties = infoboxProperties,
			episodes = episodes.map { ep ->
				TrackingSiteItemDetails.EpisodeInfo(
					number = ep.number,
					title = ep.title,
					url = ep.url,
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
		)
	}
}

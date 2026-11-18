package org.skepsun.kototoro.settings.users

import javax.inject.Inject
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerUserProfileRepository
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerRepositoryMap
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserProfile
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserStats

class TrackingUserAccountSummaryProvider @Inject constructor(
    private val repositoriesMap: ScrobblerRepositoryMap,
    private val db: MangaDatabase,
) {

    suspend fun load(service: ScrobblerService): ScrobblerUserProfile {
        val repository = repositoriesMap[service]
        val user = repository.cachedUser ?: repository.loadUser()
        return ScrobblerUserProfile(
            user = user,
            stats = buildLocalStats(service),
        )
    }

    private suspend fun buildLocalStats(service: ScrobblerService): ScrobblerUserStats? {
        val entries = db.getScrobblingDao().findAllByScrobbler(service.id)
        if (entries.isEmpty()) return null
        return when (service) {
            ScrobblerService.MAL -> buildMalStats(entries)
            ScrobblerService.SIMKL, ScrobblerService.TMDB -> buildSimklStats(entries)
            else -> buildUnifiedStats(entries)
        }
    }

    private fun buildUnifiedStats(entries: List<ScrobblingEntity>): ScrobblerUserStats {
        val animeEntries = entries.filter(::isAnimeEntry)
        val mangaEntries = entries.filterNot(::isAnimeEntry)
        return ScrobblerUserStats(
            animeCount = animeEntries.size.takeIf { it > 0 },
            mangaCount = mangaEntries.size.takeIf { it > 0 },
            episodesWatched = animeEntries.maxOfOrNull { it.chapter }?.takeIf { it > 0 },
            chaptersRead = mangaEntries.maxOfOrNull { it.chapter }?.takeIf { it > 0 },
            animeMeanScore = animeEntries.meanRatingOrNull(),
            mangaMeanScore = mangaEntries.meanRatingOrNull(),
        )
    }

    private fun buildMalStats(entries: List<ScrobblingEntity>): ScrobblerUserStats {
        val animeEntries = entries.filter { it.mediaType == "anime" }
        val mangaEntries = entries.filter { it.mediaType == "manga" }
        return ScrobblerUserStats(
            animeCount = animeEntries.size.takeIf { it > 0 },
            mangaCount = mangaEntries.size.takeIf { it > 0 },
            episodesWatched = animeEntries.maxOfOrNull { it.chapter }?.takeIf { it > 0 },
            chaptersRead = mangaEntries.maxOfOrNull { it.chapter }?.takeIf { it > 0 },
            animeMeanScore = animeEntries.meanRatingOrNull(),
            mangaMeanScore = mangaEntries.meanRatingOrNull(),
        )
    }

    private fun buildSimklStats(entries: List<ScrobblingEntity>): ScrobblerUserStats {
        val animeEntries = entries.filter { it.mediaType.equals("anime", ignoreCase = true) }
        val movieEntries = entries.filter { it.mediaType.equals("movie", ignoreCase = true) }
        val tvEntries = entries.filter { it.mediaType.equals("tv", ignoreCase = true) }
        return ScrobblerUserStats(
            animeCount = animeEntries.size.takeIf { it > 0 },
            tvCount = tvEntries.size.takeIf { it > 0 },
            movieCount = movieEntries.size.takeIf { it > 0 },
            episodesWatched = animeEntries.maxOfOrNull { it.chapter }?.takeIf { it > 0 },
            tvEpisodesWatched = tvEntries.maxOfOrNull { it.chapter }?.takeIf { it > 0 },
        )
    }

    private fun isAnimeEntry(entity: ScrobblingEntity): Boolean {
        val mediaType = entity.mediaType.lowercase()
        return mediaType == "anime" ||
            mediaType == "2" ||
            mediaType == "tv" ||
            mediaType == "movie" ||
            mediaType == "ona" ||
            mediaType == "ova"
    }

    private fun List<ScrobblingEntity>.meanRatingOrNull(): Double? {
        val rated = filter { it.rating > 0f }
        if (rated.isEmpty()) return null
        return rated.sumOf { (it.rating * 10f).toDouble() } / rated.size
    }
}

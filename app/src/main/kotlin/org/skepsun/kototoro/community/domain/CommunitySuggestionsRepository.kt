package org.skepsun.kototoro.community.domain

import org.skepsun.kototoro.community.data.CommunitySnapshotRepository
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Community recommendations and charts for the suggestions page (plan §7.3). The library is matched against the index
 * on the device; only the index's public files are downloaded, and nothing about the library is sent anywhere.
 */
@Singleton
class CommunitySuggestionsRepository @Inject constructor(
    private val settings: AppSettings,
    private val db: MangaDatabase,
    private val suggestions: SuggestionRepository,
    private val snapshots: CommunitySnapshotRepository,
) {

    suspend fun recommendations(limit: Int = LIMIT): List<CommunityRecommendation> {
        if (!settings.isCommunityEnabled) return emptyList()
        val library = suggestions.getLibraryWorks(LIBRARY_HISTORY).filter { it.id != 0L }
        if (library.isEmpty()) return emptyList()
        val dao = db.getCommunityDao()
        val myStars = dao.findAllRatings().associate { it.mangaId to it.stars }
        val refs = libraryRefs()
        val keysByWork = library.associate { work ->
            work.id to CommunityWorkKeys.keys(
                CommunityMedium.of(work.contentType), work.titles, refs[work.id].orEmpty(), CommunityRepository.Companion::fold,
            )
        }.filterValues { it.isNotEmpty() }
        val groups = snapshots.groupsOf(keysByWork)
        val seeds = library.mapNotNull { work ->
            val group = groups[work.id] ?: return@mapNotNull null
            val affinity = CommunityRecommendations.affinity(myStars[work.id], work.isFavourite, work.percent) ?: return@mapNotNull null
            CommunitySeed(group, work.titles.firstOrNull(), affinity)
        }
        if (seeds.isEmpty()) return emptyList()
        val summaries = snapshots.works(seeds.map { it.groupId }.distinct())
        // A work the index never grouped with the library's copy can still be on the shelf under the same title.
        val libraryTitleKeys = keysByWork.values.flatten().filterNot(CommunityWorkKeys::isStrong).toHashSet()
        val allowNsfw = settings.isCommunityNsfwRatingEnabled
        return CommunityRecommendations.rank(
            seeds = seeds,
            similar = { summaries[it]?.similar.orEmpty() },
            known = groups.values.toSet(),
            excluded = { work -> (work.nsfw && !allowNsfw) || work.title?.let { isKnownTitle(it, libraryTitleKeys) } == true },
            limit = limit,
        )
    }

    /** The community's best rated works in [window] (`week`, `month`, `all`) across media, best first. */
    suspend fun popular(window: String, limit: Int = LIMIT): List<CommunityChartEntry> {
        if (!settings.isCommunityEnabled) return emptyList()
        val nsfw = listOf(false) + listOfNotNull(true.takeIf { settings.isCommunityNsfwRatingEnabled })
        return MEDIA.flatMap { medium -> nsfw.flatMap { snapshots.chart(window, medium, it)?.entries.orEmpty() } }
            .sortedWith(compareByDescending<CommunityChartEntry> { it.score }.thenByDescending { it.count })
            .take(limit)
    }

    /** Tracker ids for every library work: the reader's own bindings, then what searches bound. */
    private suspend fun libraryRefs(): Map<Long, List<CommunityExternalRef>> {
        val bound = db.getCommunityDao().findBoundResolutions().mapNotNull { row ->
            val service = CommunityExternalService.of(row.service) ?: return@mapNotNull null
            row.mangaId to CommunityExternalRef(service, row.remoteId ?: return@mapNotNull null)
        }
        val tracked = db.getScrobblingDao().findAllByScrobblerEntries().mapNotNull { row ->
            val name = ScrobblerService.entries.firstOrNull { it.id == row.scrobbler }?.name
            val service = CommunityExternalService.of(name) ?: return@mapNotNull null
            row.mangaId to CommunityExternalRef(service, row.targetId)
        }
        return (tracked + bound).groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.distinctBy { it.service } }
    }

    private fun isKnownTitle(title: String, libraryTitleKeys: Set<String>): Boolean =
        CommunityMedium.entries.any { medium -> CommunityWorkKeys.titleKey(title, medium, CommunityRepository.Companion::fold) in libraryTitleKeys }

    private companion object {
        const val LIMIT = 20
        const val LIBRARY_HISTORY = 500
        val MEDIA = listOf("comic", "novel", "video")
    }
}

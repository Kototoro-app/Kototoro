package org.skepsun.kototoro.community.domain

import org.skepsun.kototoro.community.data.CommunitySnapshotRepository
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.suggestions.domain.LibraryWork
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
    private val snapshots: CommunitySnapshotRepository,
) {

    suspend fun cachedRatings(candidates: Collection<Content>): Map<Long, CommunityRatingSummary> {
        if (!settings.isCommunityEnabled || candidates.isEmpty()) return emptyMap()
        return runCatchingCancellable {
            val keysById = candidates.associate { item ->
                item.id to CommunityWorkKeys.keys(
                    CommunityMedium.of(item.source.getContentType().name),
                    listOf(item.title) + item.altTitles,
                    fold = CommunityRepository.Companion::fold,
                )
            }.filterValues { it.isNotEmpty() }
            val groups = snapshots.groupsOf(keysById, cachedOnly = true)
            val works = snapshots.works(groups.values.toSet(), cachedOnly = true)
            groups.mapNotNull { (id, group) ->
                val work = works[group] ?: return@mapNotNull null
                if (work.nsfw && !settings.isCommunityNsfwRatingEnabled) return@mapNotNull null
                work.rating?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    suspend fun suggestionTargets(library: List<LibraryWork>): List<CommunitySuggestionTarget> {
        if (!settings.isCommunityEnabled || library.isEmpty()) return emptyList()
        return CommunityRecommendations.targets(
            library = library,
            stars = db.getCommunityDao().findAllRatings().associate { it.mangaId to it.stars },
            refs = libraryRefs(),
            fold = CommunityRepository.Companion::fold,
            groupsOf = { snapshots.groupsOf(it) },
            works = { snapshots.works(it) },
            limit = LIMIT,
        )
    }

    /** The community's best rated works in [window] (`week`, `month`, `all`) across media, best first. */
    suspend fun popular(window: String, limit: Int = LIMIT): List<CommunityChartEntry> {
        if (!settings.isCommunityEnabled) return emptyList()
        val nsfw = listOf(false) + listOfNotNull(true.takeIf { settings.isCommunityNsfwRatingEnabled })
        return CommunityHighlights.merge(MEDIA.flatMap { medium -> nsfw.map { snapshots.chart(window, medium, it) } }, limit)
    }

    /** Every work readers rated, best first, for while no chart has entries ([CommunityHighlights]). */
    suspend fun rated(limit: Int = LIMIT): List<CommunityChartEntry> {
        if (!settings.isCommunityEnabled) return emptyList()
        return CommunityHighlights.rated(snapshots.allWorks(), settings.isCommunityNsfwRatingEnabled, limit)
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

    private companion object {
        const val LIMIT = 20
        val MEDIA = listOf("comic", "novel", "video")
    }
}

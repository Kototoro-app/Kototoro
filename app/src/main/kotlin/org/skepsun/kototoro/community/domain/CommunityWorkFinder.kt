package org.skepsun.kototoro.community.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.community.ui.CommunitySourceChoice
import org.skepsun.kototoro.core.library.community.CommunitySourceOption
import org.skepsun.kototoro.core.library.community.CommunityService
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.source.toSourceRef
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.source.AndroidSourceRuntime
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.toSourceContent
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A community work from a chart or a similar-works rail as something to open (the shared [CommunityService.findWork]):
 * the reader's own copy, else the first enabled source whose search finds it, preferred recommendation sources and the
 * ones the library reads from first. Null when nothing matches, and the caller searches every source for the title.
 */
@Singleton
class CommunityWorkFinder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: CommunityService,
    private val sourcesRepository: ContentSourcesRepository,
    private val runtime: AndroidSourceRuntime,
    private val favourites: FavouritesRepository,
    private val history: HistoryRepository,
    private val settings: AppSettings,
) {

    suspend fun find(groupId: String, title: String): Content? = runCatchingCancellable {
        val (owned, sources) = librarySources()
        val library = owned.map { it.toSourceContent() }
        val found = service.findWork(groupId, title, library, sources.map { it.ref }, hostOf(sources)) { source, query ->
            runtime.getList(source.name, 0, null, SourceFilter(query = query))
        } ?: return@runCatchingCancellable null
        owned.firstOrNull { it.id == found.id } ?: runtime.toParserContent(found)
    }.getOrNull()

    /** The "other sources" list: the reader's sources the work's readers named, then the rest, with display names. */
    suspend fun options(groupId: String): List<Pair<CommunitySourceOption, CommunitySourceChoice>> = runCatchingCancellable {
        val (_, sources) = librarySources()
        val names = sources.associate { it.ref.name to it.title }
        service.sourceOptions(groupId, sources.map { it.ref }, hostOf(sources), includeOthers = true).map { option ->
            val listed = option.listed
            option to CommunitySourceChoice(
                key = option.source.name,
                name = names[option.source.name] ?: option.source.name,
                readers = listed?.readers,
                sameSite = listed != null && listed.source != option.source.name,
            )
        }
    }.getOrDefault(emptyList())

    /** The work on one source the reader picked; null when it is not there. */
    suspend fun findIn(option: CommunitySourceOption, groupId: String, title: String): Content? = runCatchingCancellable {
        service.findWorkIn(option, groupId, title) { source, query ->
            runtime.getList(source.name, 0, null, SourceFilter(query = query))
        }?.let(runtime::toParserContent)
    }.getOrNull()

    private class Source(val ref: SourceRef, val title: String, val host: String?)

    /** The library, and the enabled sources: preferred recommendation sources, then the ones the library reads from. */
    private suspend fun librarySources(): Pair<List<Content>, List<Source>> {
        val owned = (favourites.getAllContent() + history.getAllForLibrarySearch()).distinctBy { it.id }
        val preferred = settings.suggestionsPreferredSources
        val used = owned.mapTo(HashSet()) { it.source.name }
        val sources = sourcesRepository.getEnabledSources().map { source ->
            // Android's source model carries no site address, so the same-site match is the desktop's only.
            Source(source.toSourceRef(), source.getTitle(context), host = null)
        }.sortedWith(compareByDescending<Source> { it.ref.name in preferred }.thenByDescending { it.ref.name in used })
        return owned to sources
    }

    private fun hostOf(sources: List<Source>): (SourceRef) -> String? {
        val hosts = sources.associate { it.ref.name to it.host }
        return { hosts[it.name] }
    }
}

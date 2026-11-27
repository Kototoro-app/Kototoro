package org.skepsun.kototoro.core.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.CONTENT_TYPE_HENTAI_PREFIX
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.isNsfw
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.suggestions.domain.*

data class SourceSuggestionPreferences(
    val excludeNsfw: Boolean = false,
    val tagBlacklist: Set<String> = emptySet(),
    val tagWhitelist: Set<String> = emptySet(),
    val preferredSources: Set<String> = emptySet(),
    val excludedSources: Set<String> = emptySet(),
    val language: SuggestionLanguage = SuggestionLanguage.SYSTEM,
    val globalTagBlacklist: Set<String> = emptySet(),
    val languageOnly: Boolean = false,
    val systemLanguage: String = "en",
    /** Whether a source's share of the suggestions follows how much of the library came from it. */
    val weighSourcesByUse: Boolean = true,
)

/** Applies current preferences to cached rows too, including rows generated before the preferences changed. */
fun SourceSuggestionPreferences.filter(items: List<SourceContent>): List<SourceContent> {
    val blacklist = SuggestionTagBlacklist(tagBlacklist, ::suggestionTagsMatch)
    val global = org.skepsun.kototoro.core.model.TagTitleBlacklist(globalTagBlacklist)
    return items.filter { item ->
        item.source.name !in excludedSources && (!languageOnly || language.accepts(item.source.locale, systemLanguage)) &&
            (!excludeNsfw || (item.contentRating != "ADULT" && !item.source.contentType.startsWith("HENTAI_"))) &&
            !blacklist.containsAny(item.tags.map { it.title }) && !global.containsAnyTitle(item.tags.map { it.title })
    }.sortedByDescending { language.accepts(it.source.locale, systemLanguage) }
}

data class SourceSuggestionResult(val items: List<RankedSuggestion<SourceContent>>, val successfulSources: Int)

data class SourceSuggestionCommunity(
    val candidates: List<SuggestionCandidate<SourceContent>>,
    val ratings: Map<Long, org.skepsun.kototoro.community.domain.CommunityRatingSummary>,
)

/** Source-protocol implementation of Android's worker rules, used by Apple and desktop hosts. */
class SourceSuggestions(
    private val runtime: SourceRuntime,
    private val matches: SuggestionTagMatcher,
    private val limits: SuggestionLimits = SuggestionLimits(),
) {
    /**
     * @param library what the reader has read and saved, see [SourceLibrary.libraryWorks]
     * @param seed picks which of the reader's genres each source is asked for; the same seed asks the same things
     */
    suspend fun generate(
        library: List<LibraryWork>,
        sources: List<SourceRef>,
        preferences: SourceSuggestionPreferences,
        now: Long,
        seed: Long = 0L,
        community: (suspend (List<SourceContent>) -> SourceSuggestionCommunity)? = null,
    ): SourceSuggestionResult {
        // An empty library still gets the sources' popular and new lists; there is just no taste to ask for yet.
        val profile = SuggestionProfile.of(library, now, preferences.tagWhitelist)
        val blacklist = SuggestionTagBlacklist(preferences.tagBlacklist, matches)
        val global = org.skepsun.kototoro.core.model.TagTitleBlacklist(preferences.globalTagBlacklist)
        // Works the run would drop anyway: they must not count as what a page still offers.
        fun SourceContent.isExcluded() =
            (preferences.excludeNsfw && (contentRating == "ADULT" || source.contentType.startsWith("HENTAI_"))) ||
                blacklist.containsAny(tags.map { tag -> tag.title }) || global.containsAnyTitle(tags.map { tag -> tag.title })
        val lock = Mutex()
        var successful = 0
        val raw = collectSourceResults(sources.filterNot {
            it.name in preferences.excludedSources ||
                (preferences.languageOnly && !preferences.language.accepts(it.locale, preferences.systemLanguage)) ||
                (preferences.excludeNsfw && it.contentType.startsWith("HENTAI_"))
        }.sortedWith(
            compareByDescending<SourceRef> { preferences.language.accepts(it.locale, preferences.systemLanguage) }
                // The ones the reader reads from first: when the run is cut off, the ones they never opened go.
                .thenByDescending { if (preferences.weighSourcesByUse) profile.useOf(it.name) else 0 },
        )) { source ->
            val descriptor = runtime.describe(source.name)
            val orders = descriptor.sortOrders
            val available = try {
                runtime.getFilterOptions(source.name).availableTags
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            val taste = profile.forDomain(source.contentType.startsWith(CONTENT_TYPE_HENTAI_PREFIX))
            val queries = taste.planQueries(source.name, seed, orders, available, { it.title },
                { blacklist.containsTitle(it.title) }, matches, limits)
            var answered = false
            val found = queries.flatMap { query ->
                val filter = query.tag?.let { SourceFilter(tags = setOf(it)) }
                val first = try {
                    runtime.getList(source.name, 0, query.order, filter)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@flatMap emptyList()
                }
                answered = true
                // A reader with a big library already has the top of any list, and a list mostly of filtered works
                // offers little either: one page further when little usable is left.
                val fresh = first.count { !it.isExcluded() && !profile.knows(it.id, listOf(it.title) + it.altTitles) }
                val list = if (first.isNotEmpty() && fresh < limits.maxSourceResults / 2) {
                    first + try {
                        val offset = if (descriptor.pagingMode == SourcePagingMode.PAGE_INDEX) 1 else first.size
                        runtime.getList(source.name, offset, query.order, filter)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else first
                cleanSuggestionList(
                    items = list.mapIndexed { rank, item -> SuggestionCandidate(item, query.direction, query.reason, rank) }
                        .filterNot { profile.knows(it.item.id, listOf(it.item.title) + it.item.altTitles) },
                    title = { it.item.title },
                    hasAddress = { it.item.url.isNotBlank() || it.item.publicUrl.isNotBlank() },
                    excluded = { it.item.isExcluded() },
                    limits = limits,
                    // Only what was asked for by taste is picked by taste; the other lists keep the source's order
                    fit = { candidate ->
                        if (candidate.direction != SuggestionDirection.TASTE) 0.0
                        else taste.affinity(suggestionTagKeys(candidate.item.tags.map { tag -> tag.title }))
                    },
                )
            }
            if (answered) lock.withLock { successful++ }
            found
        }
        val extra = if (successful > 0) community?.invoke(raw.map { it.item }) else null
        return SourceSuggestionResult(rankSuggestions(
            candidates = raw + extra?.candidates.orEmpty(),
            profile = profile,
            id = { it.id },
            source = { it.source.name },
            titles = { listOf(it.title) + it.altTitles },
            tags = { it.tags.map { tag -> tag.title } },
            preferredSources = preferences.preferredSources - preferences.excludedSources,
            languagePriority = { preferences.language.accepts(it.source.locale, preferences.systemLanguage) },
            isAdult = { it.isNsfw },
            weighSources = preferences.weighSourcesByUse,
            limits = limits,
            quality = { item ->
                val rating = extra?.ratings?.get(item.id)
                SuggestionQuality.of(item.rating, rating?.score, rating?.weight)
            },
        ), successful)
    }
}

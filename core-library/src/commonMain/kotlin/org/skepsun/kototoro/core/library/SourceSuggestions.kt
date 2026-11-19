package org.skepsun.kototoro.core.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.SourceContent
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

/** Source-protocol implementation of Android's worker rules, used by Apple and desktop hosts. */
class SourceSuggestions(
    private val runtime: SourceRuntime,
    private val matches: SuggestionTagMatcher,
    private val limits: SuggestionLimits = SuggestionLimits(),
) {
    /**
     * @param library what the reader has read and saved, see [SourceLibrary.libraryWorks]
     * @param ages when each suggestion still on the list was first put there, see [SourceLibrary.suggestionAges]
     * @param seed what the part of the list that rotates is drawn with; the same seed gives the same list
     */
    suspend fun generate(
        library: List<LibraryWork>,
        sources: List<SourceRef>,
        preferences: SourceSuggestionPreferences,
        now: Long,
        ages: Map<Long, Long> = emptyMap(),
        seed: Long = now,
    ): SourceSuggestionResult {
        if (library.isEmpty() && preferences.tagWhitelist.isEmpty()) return SourceSuggestionResult(emptyList(), 0)
        val profile = SuggestionProfile.of(library, now, preferences.tagWhitelist)
        val blacklist = SuggestionTagBlacklist(preferences.tagBlacklist, matches)
        val global = org.skepsun.kototoro.core.model.TagTitleBlacklist(preferences.globalTagBlacklist)
        val lock = Mutex()
        var successful = 0
        val raw = collectSourceResults(sources.filterNot {
            it.name in preferences.excludedSources ||
                (preferences.languageOnly && !preferences.language.accepts(it.locale, preferences.systemLanguage)) ||
                (preferences.excludeNsfw && it.contentType.startsWith("HENTAI_"))
        }.sortedByDescending { preferences.language.accepts(it.locale, preferences.systemLanguage) }) { source ->
            val descriptor = runtime.describe(source.name)
            val orders = descriptor.sortOrders
            val available = try {
                runtime.getFilterOptions(source.name).availableTags
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            val queries = profile.planQueries(source.name, seed, orders, available, { it.title },
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
                // A reader with a big library already has the top of any list: one page further when little is left.
                val fresh = first.count { !profile.knows(it.id, listOf(it.title) + it.altTitles) }
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
                    excluded = {
                        (preferences.excludeNsfw && (it.item.contentRating == "ADULT" ||
                            it.item.source.contentType.startsWith("HENTAI_"))) ||
                            blacklist.containsAny(it.item.tags.map { tag -> tag.title }) ||
                            global.containsAnyTitle(it.item.tags.map { tag -> tag.title })
                    },
                    limits = limits,
                    // Only what was asked for by taste is picked by taste; the other lists keep the source's order
                    fit = { candidate ->
                        if (candidate.direction != SuggestionDirection.TASTE) 0.0
                        else profile.affinity(suggestionTagKeys(candidate.item.tags.map { tag -> tag.title }))
                    },
                )
            }
            if (answered) lock.withLock { successful++ }
            found
        }
        return SourceSuggestionResult(rankSuggestions(
            candidates = raw,
            profile = profile,
            id = { it.id },
            source = { it.source.name },
            titles = { listOf(it.title) + it.altTitles },
            tags = { it.tags.map { tag -> tag.title } },
            now = now,
            seed = seed,
            firstSuggestedAt = { ages[it.id] },
            preferredSources = preferences.preferredSources - preferences.excludedSources,
            languagePriority = { preferences.language.accepts(it.source.locale, preferences.systemLanguage) },
            limits = limits,
            groupOf = { SuggestionOrigins.groupOf(it.source.contentType, it.tags.map { tag -> tag.title }, it.source.locale) },
        ), successful)
    }
}

package org.skepsun.kototoro.core.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.suggestions.domain.*

data class SourceSuggestionPreferences(
    val excludeNsfw: Boolean = false,
    val tagBlacklist: Set<String> = emptySet(),
    val tagWhitelist: Set<String> = emptySet(),
    val preferredSources: Set<String> = emptySet(),
    val excludedSources: Set<String> = emptySet(),
)

data class SourceSuggestionResult(val items: List<RankedSuggestion<SourceContent>>, val successfulSources: Int)

/** Source-protocol implementation of Android's worker rules, used by Apple and desktop hosts. */
class SourceSuggestions(
    private val runtime: SourceRuntime,
    private val matches: SuggestionTagMatcher,
    private val limits: SuggestionLimits = SuggestionLimits(),
    private val shuffle: (MutableList<SourceContent>) -> Unit = { it.shuffle() },
) {
    suspend fun generate(
        seed: List<SourceContent>,
        sources: List<SourceRef>,
        preferences: SourceSuggestionPreferences,
    ): SourceSuggestionResult {
        if (seed.isEmpty() && preferences.tagWhitelist.isEmpty()) return SourceSuggestionResult(emptyList(), 0)
        val tags = suggestionSeedTags(preferences.tagWhitelist.toList(),
            seed.flatMap { it.tags.map { tag -> tag.title } })
        val blacklist = SuggestionTagBlacklist(preferences.tagBlacklist, matches)
        val lock = Mutex()
        var successful = 0
        val raw = collectSourceResults(sources.filterNot {
            it.name in preferences.excludedSources || (preferences.excludeNsfw && it.contentType.startsWith("HENTAI_"))
        }) { source ->
            val order = pickSuggestionSortOrder(runtime.describe(source.name).sortOrders)
            val available = try {
                runtime.getFilterOptions(source.name).availableTags
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            val tag = pickSuggestionTag(tags, available, { it.title }, { blacklist.containsTitle(it.title) }, matches)
            val tagged = runtime.getList(source.name, 0, order, tag?.let { SourceFilter(tags = setOf(it)) })
            val list = if (tagged.isEmpty() && tag != null) runtime.getList(source.name, 0, order, null) else tagged
            lock.withLock { successful++ }
            cleanSuggestionList(list, { it.title }, { it.url.isNotBlank() || it.publicUrl.isNotBlank() }, {
                (preferences.excludeNsfw &&
                    (it.contentRating == "ADULT" || it.source.contentType.startsWith("HENTAI_"))) ||
                    blacklist.containsAny(it.tags.map { tag -> tag.title })
            }, limits, shuffle)
        }
        return SourceSuggestionResult(rankSuggestions(raw,
            { suggestionRelevance(it.tags.map { tag -> tag.title }, tags, matches) }, { it.id }, { it.source.name },
            preferences.preferredSources - preferences.excludedSources, limits), successful)
    }
}

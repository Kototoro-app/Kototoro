package org.skepsun.kototoro.ios

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.library.SourceSuggestionPreferences
import org.skepsun.kototoro.core.library.SourceSuggestions
import org.skepsun.kototoro.core.library.filter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.suggestions.domain.SuggestionLimits
import org.skepsun.kototoro.suggestions.domain.SuggestionTagBlacklist
import org.skepsun.kototoro.suggestions.domain.parseSuggestionTags
import org.skepsun.kototoro.suggestions.domain.suggestionTagsMatch

data class IosSuggestionSettings(
    val excludeNsfw: Boolean,
    val preferredTags: String,
    val excludedTags: String,
    val preferredSources: List<String>,
    val excludedSources: List<String>,
    val language: String = "SYSTEM",
    val languageOnly: Boolean = false,
    val systemLanguage: String = "en",
) {
    internal fun preferences() = SourceSuggestionPreferences(excludeNsfw, parseSuggestionTags(excludedTags),
        parseSuggestionTags(preferredTags), preferredSources.toSet(), excludedSources.toSet(),
        org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.of(language),
        languageOnly = languageOnly, systemLanguage = systemLanguage)
}

/** Uses the same suggestions table and ranking as Android; Swift owns opt-in and foreground scheduling. */
class IosSuggestions internal constructor(private val library: IosLibrary) {
    private val refreshLock = Mutex()

    @Throws(Exception::class)
    suspend fun items(sources: List<IosSource>, settings: IosSuggestionSettings): List<IosSourceContent> {
        val allowed = sources.filterNot { it.isAdult && settings.excludeNsfw }.mapTo(HashSet()) { it.name }
        val blacklist = SuggestionTagBlacklist(parseSuggestionTags(settings.excludedTags), ::suggestionTagsMatch)
        val reasons = library.records.suggestionReasons()
        // A cold cache may be read before the library's source lookup is connected to the current catalogue.
        val catalogue = sources.associateBy { it.name }
        val cached = library.records.suggestions(SuggestionLimits().maxResults).map { content ->
            catalogue[content.source.name]?.let { source ->
                content.copy(source = SourceRef(source.name, source.locale, source.contentType))
            } ?: content
        }
        return settings.preferences().filter(cached).filter {
            it.source.name in allowed && it.source.name !in settings.excludedSources &&
                (!settings.excludeNsfw || it.contentRating != "ADULT") &&
                !blacklist.containsAny(it.tags.map { tag -> tag.title })
        }.map { content ->
            val reason = reasons[content.id]
            IosSourceContent(content, suggestionDirection = reason?.first?.name ?: "TASTE", suggestionReason = reason?.second)
        }
    }

    @Throws(Exception::class)
    suspend fun refresh(
        runtime: IosSources,
        sources: List<IosSource>,
        settings: IosSuggestionSettings,
        cancellation: IosCancellation,
    ): Int = coroutineScope {
        val work = async {
            if (cancellation.signal.isCompleted) throw CancellationException("Suggestions cancelled")
            refreshLock.withLock {
                val limits = SuggestionLimits()
                val works = library.records.libraryWorks(limits.libraryHistory)
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val candidates = sources.filterNot { it.kind == "local" }.shuffled()
                    .map { SourceRef(it.name, it.locale, it.contentType) }
                val result = SourceSuggestions(runtime.runtime, ::suggestionTagsMatch)
                    .generate(works, candidates, settings.preferences(), now, library.records.suggestionAges())
                currentCoroutineContext().ensureActive()
                // Failed sources must not wipe a usable cache or postpone the next retry for six hours.
                check(result.successfulSources > 0) { localized("暂未获取到推荐，请检查来源或网络后重试") }
                library.records.replaceSuggestions(result.items, now)
            }
        }
        val watcher = launch { cancellation.signal.await(); work.cancel() }
        try { work.await() } finally { watcher.cancel() }
    }
}

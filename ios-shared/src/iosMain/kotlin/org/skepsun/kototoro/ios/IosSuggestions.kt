package org.skepsun.kototoro.ios

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.library.SourceSuggestionPreferences
import org.skepsun.kototoro.core.library.SourceSuggestions
import org.skepsun.kototoro.core.library.SourceSuggestionCommunity
import org.skepsun.kototoro.core.library.filter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.community.domain.*
import org.skepsun.kototoro.suggestions.domain.LibraryWork
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection
import org.skepsun.kototoro.suggestions.domain.SuggestionLimits
import org.skepsun.kototoro.suggestions.domain.SuggestionProfile
import org.skepsun.kototoro.suggestions.domain.SuggestionTagBlacklist
import org.skepsun.kototoro.suggestions.domain.parseSuggestionTags
import org.skepsun.kototoro.suggestions.domain.suggestionRotationSeed
import org.skepsun.kototoro.suggestions.domain.suggestionTagsMatch
import org.skepsun.kototoro.tracking.discovery.PublicTrackingSite
import kotlin.random.Random

data class IosSuggestionSettings(
    val excludeNsfw: Boolean,
    val preferredTags: String,
    val excludedTags: String,
    val preferredSources: List<String>,
    val excludedSources: List<String>,
    val language: String = "SYSTEM",
    val languageOnly: Boolean = false,
    val systemLanguage: String = "en",
    val communityEnabled: Boolean = false,
) {
    internal fun preferences() = SourceSuggestionPreferences(excludeNsfw, parseSuggestionTags(excludedTags),
        parseSuggestionTags(preferredTags), preferredSources.toSet(), excludedSources.toSet(),
        org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.of(language),
        languageOnly = languageOnly, systemLanguage = systemLanguage)
}

/** Uses the same suggestions table and ranking as Android; Swift owns opt-in and foreground scheduling. */
class IosSuggestions internal constructor(
    private val library: IosLibrary,
    readSnapshot: (suspend (String) -> String?)? = null,
) {
    private val refreshLock = Mutex()
    private val communityIndex = readSnapshot?.let(::IosCommunityIndex) ?: library.communityIndex

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
        }.mapNotNull { content ->
            val reason = reasons[content.id]
            if (!settings.communityEnabled && reason?.first == SuggestionDirection.COMMUNITY) return@mapNotNull null
            IosSourceContent(content, suggestionDirection = reason?.first?.name ?: "TASTE", suggestionReason = reason?.second)
        }
    }

    @Throws(Exception::class)
    suspend fun refresh(
        runtime: IosSources,
        sources: List<IosSource>,
        settings: IosSuggestionSettings,
        cancellation: IosCancellation,
        manual: Boolean = false,
    ): Int = coroutineScope {
        val work = async {
            if (cancellation.signal.isCompleted) throw CancellationException("Suggestions cancelled")
            refreshLock.withLock {
                val limits = SuggestionLimits()
                val works = library.records.libraryWorks(limits.libraryHistory)
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val seed = suggestionRotationSeed(now, refreshAt = if (manual) now else 0L)
                val candidates = sources.filterNot { it.kind == "local" }.sortedBy { it.name }.shuffled(Random(seed))
                    .map { SourceRef(it.name, it.locale, it.contentType) }
                val preferences = settings.preferences()
                val safeSourceNames = sources.filterNot { it.isAdult }.mapTo(HashSet()) { it.name }
                val communitySources = candidates.filter { it.name in safeSourceNames }
                val result = SourceSuggestions(runtime.runtime, ::suggestionTagsMatch)
                    .generate(works, candidates, preferences, now, library.records.suggestionAges(), seed = seed,
                        community = if (!settings.communityEnabled) null else { raw ->
                            community(runtime.runtime, works, communitySources, preferences, now, raw)
                        })
                currentCoroutineContext().ensureActive()
                // Failed sources must not wipe a usable cache or postpone the next retry for six hours.
                check(result.successfulSources > 0) { localized("暂未获取到推荐，请检查来源或网络后重试") }
                library.records.replaceSuggestions(result.items, now)
            }
        }
        val watcher = launch { cancellation.signal.await(); work.cancel() }
        try { work.await() } finally { watcher.cancel() }
    }

    private suspend fun community(
        runtime: SourceRuntime,
        libraryWorks: List<LibraryWork>,
        sources: List<SourceRef>,
        preferences: SourceSuggestionPreferences,
        now: Long,
        raw: List<SourceContent>,
    ): SourceSuggestionCommunity {
        val empty = SourceSuggestionCommunity(emptyList(), emptyMap())
        return communityAttempt {
            val index = communityIndex.snapshot() ?: return@communityAttempt empty
            val targets = withTimeoutOrNull(10_000L) {
                val dao = library.database.getCommunityDao()
                val bound = dao.findBoundResolutions().mapNotNull { row ->
                    val service = CommunityExternalService.of(row.service) ?: return@mapNotNull null
                    row.mangaId to CommunityExternalRef(service, row.remoteId ?: return@mapNotNull null)
                }
                val tracked = library.database.getScrobblingDao().findAllByScrobblerEntries().mapNotNull { row ->
                    val name = PublicTrackingSite.entries.firstOrNull { it.id == row.scrobbler }?.name
                    val service = CommunityExternalService.of(name) ?: return@mapNotNull null
                    row.mangaId to CommunityExternalRef(service, row.targetId)
                }
                val refs = (tracked + bound).groupBy({ it.first }, { it.second })
                    .mapValues { (_, values) -> values.distinctBy { it.service } }
                CommunityRecommendations.targets(
                    libraryWorks, dao.findAllRatings().associate { it.mangaId to it.stars }, refs,
                    ::foldCommunityTitle, { index.groups(it) }, { index.works(it) },
                )
            }.orEmpty()
            val used = libraryWorks.mapNotNullTo(HashSet()) { it.source }
            val choices = sources.filter { source -> source.name !in preferences.excludedSources &&
                (!preferences.languageOnly || preferences.language.accepts(source.locale, preferences.systemLanguage))
            }.sortedWith(compareByDescending<SourceRef> { it.name in preferences.preferredSources }
                .thenByDescending { it.name in used }).groupBy { CommunityMedium.of(it.contentType) }
            val cached = if (targets.isEmpty()) emptyMap() else
                (raw + library.records.suggestions(SuggestionLimits().maxResults)).groupBy { it.source.name }
            val profile = SuggestionProfile.of(libraryWorks, now, preferences.tagWhitelist)
            val candidates = CommunityRecommendations.resolve(
                targets, choices, { cached[it.name].orEmpty() },
                { source, title -> communityAttempt {
                    runtime.getList(source.name, 0, null, SourceFilter(query = title))
                }.getOrDefault(emptyList()) },
                { listOf(it.title) + it.altTitles },
                { source, item -> item.source.name == source.name && item.title.isNotBlank() &&
                    (item.url.isNotBlank() || item.publicUrl.isNotBlank()) &&
                    !profile.knows(item.id, listOf(item.title) + item.altTitles) &&
                    preferences.copy(excludeNsfw = true).filter(listOf(item)).isNotEmpty() },
                ::foldCommunityTitle,
            )
            val keys = raw.associate { item ->
                item.id to CommunityWorkKeys.keys(CommunityMedium.of(item.source.contentType),
                    listOf(item.title) + item.altTitles, fold = ::foldCommunityTitle)
            }
            val groups = index.groups(keys, cachedOnly = true) +
                candidates.associate { it.item.id to targets[it.rank].groupId }
            val works = index.works(groups.values, cachedOnly = true)
            SourceSuggestionCommunity(candidates, groups.mapNotNull { (id, group) ->
                works[group]?.takeUnless { it.nsfw }?.rating?.let { id to it }
            }.toMap())
        }.getOrDefault(empty)
    }
}

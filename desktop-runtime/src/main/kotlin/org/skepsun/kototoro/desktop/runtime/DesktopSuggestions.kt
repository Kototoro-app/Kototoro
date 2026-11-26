package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.extensions.SourceNsfwOverrideSet
import org.skepsun.kototoro.core.library.SourceSuggestionPreferences
import org.skepsun.kototoro.core.library.SourceSuggestions
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.parsers.util.almostEquals
import org.skepsun.kototoro.suggestions.domain.SUGGESTION_TAG_THRESHOLD
import org.skepsun.kototoro.suggestions.domain.SuggestionLimits
import org.skepsun.kototoro.suggestions.domain.SuggestionTagMatcher
import org.skepsun.kototoro.suggestions.domain.parseSuggestionTags

/** Windows counterparts of Android's suggestion settings, stored under Android's keys and defaults. */
data class DesktopSuggestionSettings(
    val enabled: Boolean = false,
    val excludeNsfw: Boolean = false,
    val tagBlacklist: Set<String> = emptySet(),
    val tagWhitelist: Set<String> = emptySet(),
    val preferredSources: Set<String> = emptySet(),
    val excludedSources: Set<String> = emptySet(),
    /** Android's `KEY_SUGGESTIONS_WIFI_ONLY`: the worker only runs on an unmetered network. */
    val wifiOnly: Boolean = false,
    /** Android's `KEY_SUGGESTIONS_DISABLED_SOURCES`: candidates come from every installed source, not only enabled. */
    val includeDisabledSources: Boolean = false,
    /** Android's `KEY_SUGGESTIONS_NOTIFICATIONS`: one recommended work is announced after a run stores results. */
    val notifications: Boolean = false,
    val language: org.skepsun.kototoro.suggestions.domain.SuggestionLanguage =
        org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.SYSTEM,
    val languageOnly: Boolean = false,
    /** `suggestions_weigh_sources`: a source's share of the suggestions follows how much of the library came from it. */
    val weighSourcesByUse: Boolean = true,
) {
    fun preferences(globalTagBlacklist: Set<String> = emptySet()) = SourceSuggestionPreferences(
        excludeNsfw, tagBlacklist, tagWhitelist, preferredSources, excludedSources, language, globalTagBlacklist,
        languageOnly, java.util.Locale.getDefault().language, weighSourcesByUse,
    )

    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_ENABLED to SourcePreferenceValue.Toggle(enabled),
        KEY_EXCLUDE_NSFW to SourcePreferenceValue.Toggle(excludeNsfw),
        KEY_EXCLUDE_TAGS to SourcePreferenceValue.Text(tagBlacklist.joinToString(", ")),
        KEY_PREFERRED_TAGS to SourcePreferenceValue.Text(tagWhitelist.joinToString(", ")),
        KEY_PREFERRED_SOURCES to SourcePreferenceValue.TextSet(preferredSources),
        KEY_EXCLUDED_SOURCES to SourcePreferenceValue.TextSet(excludedSources),
        KEY_WIFI_ONLY to SourcePreferenceValue.Toggle(wifiOnly),
        KEY_DISABLED_SOURCES to SourcePreferenceValue.Toggle(includeDisabledSources),
        KEY_NOTIFICATIONS to SourcePreferenceValue.Toggle(notifications),
        org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.PREFERENCE_KEY to SourcePreferenceValue.Text(language.name),
        org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.ONLY_PREFERENCE_KEY to SourcePreferenceValue.Toggle(languageOnly),
        KEY_WEIGH_SOURCES to SourcePreferenceValue.Toggle(weighSourcesByUse),
    )

    companion object {
        const val KEY_ENABLED = "suggestions"
        const val KEY_EXCLUDE_NSFW = "suggestions_exclude_nsfw"
        const val KEY_EXCLUDE_TAGS = "suggestions_exclude_tags"
        const val KEY_PREFERRED_TAGS = "suggestions_preferred_tags"
        const val KEY_PREFERRED_SOURCES = "suggestions_preferred_sources"
        const val KEY_EXCLUDED_SOURCES = "suggestions_excluded_sources"
        const val KEY_WEIGH_SOURCES = "suggestions_weigh_sources"
        const val KEY_WIFI_ONLY = "suggestions_wifi"
        const val KEY_DISABLED_SOURCES = "suggestions_disabled_sources"
        const val KEY_NOTIFICATIONS = "suggestions_notifications"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopSuggestionSettings(
            enabled = (values[KEY_ENABLED] as? SourcePreferenceValue.Toggle)?.value ?: false,
            excludeNsfw = (values[KEY_EXCLUDE_NSFW] as? SourcePreferenceValue.Toggle)?.value ?: false,
            tagBlacklist = parseSuggestionTags((values[KEY_EXCLUDE_TAGS] as? SourcePreferenceValue.Text)?.value),
            tagWhitelist = parseSuggestionTags((values[KEY_PREFERRED_TAGS] as? SourcePreferenceValue.Text)?.value),
            preferredSources = (values[KEY_PREFERRED_SOURCES] as? SourcePreferenceValue.TextSet)?.values.orEmpty(),
            excludedSources = (values[KEY_EXCLUDED_SOURCES] as? SourcePreferenceValue.TextSet)?.values.orEmpty(),
            wifiOnly = (values[KEY_WIFI_ONLY] as? SourcePreferenceValue.Toggle)?.value ?: false,
            includeDisabledSources = (values[KEY_DISABLED_SOURCES] as? SourcePreferenceValue.Toggle)?.value ?: false,
            notifications = (values[KEY_NOTIFICATIONS] as? SourcePreferenceValue.Toggle)?.value ?: false,
            language = org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.of(
                (values[org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.PREFERENCE_KEY] as? SourcePreferenceValue.Text)?.value,
            ),
            languageOnly = (values[org.skepsun.kototoro.suggestions.domain.SuggestionLanguage.ONLY_PREFERENCE_KEY]
                as? SourcePreferenceValue.Toggle)?.value ?: false,
            weighSourcesByUse = (values[KEY_WEIGH_SOURCES] as? SourcePreferenceValue.Toggle)?.value ?: true,
        )
    }
}

/** Android's tracker schedule settings: enabled by default at frequency 1 (a full check every 18 hours). */
data class DesktopTrackerSettings(
    val enabled: Boolean = true,
    val frequency: Float = 1f,
    val skipCompleted: Boolean = false,
    val markDuplicatesRead: Boolean = false,
) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_ENABLED to SourcePreferenceValue.Toggle(enabled),
        KEY_FREQUENCY to SourcePreferenceValue.Text(frequency.toString()),
        KEY_SKIP_COMPLETED to SourcePreferenceValue.Toggle(skipCompleted),
        KEY_MARK_DUPLICATES_READ to SourcePreferenceValue.Toggle(markDuplicatesRead),
    )

    companion object {
        const val KEY_ENABLED = "tracker_enabled"
        const val KEY_FREQUENCY = "tracker_freq"
        const val KEY_SKIP_COMPLETED = "tracker_skip_completed"
        const val KEY_MARK_DUPLICATES_READ = "tracker_mark_duplicates_read"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopTrackerSettings(
            enabled = (values[KEY_ENABLED] as? SourcePreferenceValue.Toggle)?.value ?: true,
            frequency = (values[KEY_FREQUENCY] as? SourcePreferenceValue.Text)?.value?.toFloatOrNull() ?: 1f,
            skipCompleted = (values[KEY_SKIP_COMPLETED] as? SourcePreferenceValue.Toggle)?.value ?: false,
            markDuplicatesRead = (values[KEY_MARK_DUPLICATES_READ] as? SourcePreferenceValue.Toggle)?.value ?: false,
        )
    }
}

/**
 * Generates suggestions with Android's worker rules (core-domain SuggestionRules): the reader's taste from history
 * and favourites, one tag-filtered list per installed source, ranked by how well each candidate fits that taste and
 * stored in the shared `suggestions` table.
 */
class DesktopSuggestions(
    private val library: DesktopLibrary,
    private val runtime: SourceRuntime,
    private val now: () -> Long = System::currentTimeMillis,
    private val limits: SuggestionLimits = SuggestionLimits(),
    private val sourceOrder: ((List<SourceListing>) -> List<SourceListing>)? = null,
    /** The community, when the reader turned it on: its picks join the run and its scores rank the candidates. */
    private val community: () -> org.skepsun.kototoro.core.library.community.CommunityService? = { null },
) {
    private val matcher: SuggestionTagMatcher = { a, b -> a.almostEquals(b, SUGGESTION_TAG_THRESHOLD) }

    suspend fun suggestions(limit: Int = limits.maxResults): List<SourceContent> = library.suggestions(limit)

    /**
     * Rebuilds the suggestions; returns how many were stored (0 without installed sources). Without reading history
     * they are the sources' popular and new lists.
     */
    suspend fun refresh(
        sources: List<SourceListing>,
        settings: DesktopSuggestionSettings = DesktopSuggestionSettings(),
        nsfwOverrides: SourceNsfwOverrideSet = SourceNsfwOverrideSet(),
        globalTagBlacklist: Set<String> = emptySet(),
        manual: Boolean = false,
    ): Int {
        val works = library.libraryWorks(limits.libraryHistory)
        val eligible = sources.filterNot {
            it.source.name in settings.excludedSources || (settings.excludeNsfw && it.isNsfwSource(nsfwOverrides))
        }
        if (eligible.isEmpty()) return 0
        val time = now()
        // A manual refresh asks the sources for different genres than the last run did; every other run repeats them.
        val seed = if (manual) time else 0L
        val candidates = sourceOrder?.invoke(eligible) ?: eligible.sortedBy { it.source.name }
        val preferences = settings.copy(preferredSources = settings.preferredSources - settings.excludedSources)
            .preferences(globalTagBlacklist)
        // Community picks are looked up only in sources that are not adult, whatever the reader's own nsfw choice.
        val safeSources = candidates.filterNot { it.isNsfwSource(nsfwOverrides) }.map { it.source }
        val service = community()?.takeIf { it.isEnabled }
        val result = SourceSuggestions(runtime, matcher, limits).generate(works, candidates.map { it.source },
            preferences, time, seed = seed,
            community = service?.let { { raw ->
                it.suggestionCandidates(runtime, works, safeSources, preferences, time, raw,
                    library.suggestions(limits.maxResults))
            } })
        check(result.successfulSources > 0) { "No recommendation source responded" }
        return library.replaceSuggestions(result.items, time)
    }
}


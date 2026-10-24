package org.skepsun.kototoro.desktop.runtime

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
) {
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
    )

    companion object {
        const val KEY_ENABLED = "suggestions"
        const val KEY_EXCLUDE_NSFW = "suggestions_exclude_nsfw"
        const val KEY_EXCLUDE_TAGS = "suggestions_exclude_tags"
        const val KEY_PREFERRED_TAGS = "suggestions_preferred_tags"
        const val KEY_PREFERRED_SOURCES = "suggestions_preferred_sources"
        const val KEY_EXCLUDED_SOURCES = "suggestions_excluded_sources"
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
        )
    }
}

/** Android's tracker schedule settings: enabled by default at frequency 1 (a full check every 18 hours). */
data class DesktopTrackerSettings(val enabled: Boolean = true, val frequency: Float = 1f) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_ENABLED to SourcePreferenceValue.Toggle(enabled),
        KEY_FREQUENCY to SourcePreferenceValue.Text(frequency.toString()),
    )

    companion object {
        const val KEY_ENABLED = "tracker_enabled"
        const val KEY_FREQUENCY = "tracker_freq"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopTrackerSettings(
            enabled = (values[KEY_ENABLED] as? SourcePreferenceValue.Toggle)?.value ?: true,
            frequency = (values[KEY_FREQUENCY] as? SourcePreferenceValue.Text)?.value?.toFloatOrNull() ?: 1f,
        )
    }
}

/**
 * Generates suggestions with Android's worker rules (core-domain SuggestionRules): seed tags from recent history and
 * favourites, one tag-filtered list per installed source, tag relevance and source-balanced ranking, stored in the
 * shared `suggestions` table.
 */
class DesktopSuggestions(
    private val library: DesktopLibrary,
    private val runtime: SourceRuntime,
    private val now: () -> Long = System::currentTimeMillis,
    private val limits: SuggestionLimits = SuggestionLimits(),
    private val shuffle: (MutableList<SourceContent>) -> Unit = { it.shuffle() },
    private val sourceOrder: (List<SourceListing>) -> List<SourceListing> = { it.shuffled() },
) {
    private val matcher: SuggestionTagMatcher = { a, b -> a.almostEquals(b, SUGGESTION_TAG_THRESHOLD) }

    suspend fun suggestions(limit: Int = limits.maxResults): List<SourceContent> = library.suggestions(limit)

    /** Rebuilds the suggestions; returns how many were stored (0 without reading history or installed sources). */
    suspend fun refresh(sources: List<SourceListing>, settings: DesktopSuggestionSettings = DesktopSuggestionSettings()): Int {
        val seed = (library.history().take(limits.seedItems) + library.favourites().take(limits.seedItems))
            .distinctBy { it.id }
        val candidates = sourceOrder(sources.filterNot {
            it.source.name in settings.excludedSources || (settings.excludeNsfw && it.isNsfw())
        })
        if (seed.isEmpty() || candidates.isEmpty()) return 0
        val result = SourceSuggestions(runtime, matcher, limits, shuffle).generate(seed, candidates.map { it.source },
            SourceSuggestionPreferences(settings.excludeNsfw, settings.tagBlacklist, settings.tagWhitelist,
                settings.preferredSources, settings.excludedSources))
        return library.replaceSuggestions(result.items.map { it.item to it.relevance }, now())
    }
}

private fun SourceListing.isNsfw() = source.contentType.startsWith("HENTAI_")

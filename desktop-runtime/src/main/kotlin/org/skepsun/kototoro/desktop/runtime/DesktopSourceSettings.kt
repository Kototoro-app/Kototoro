package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.extensions.SourceNsfwOverrideSet
import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.core.model.TagTitleBlacklist
import org.skepsun.kototoro.core.model.TagWarnings
import org.skepsun.kototoro.core.source.CONTENT_TYPE_HENTAI_PREFIX
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.isNsfw

/**
 * Android's `SourcesSortOrder` reduced to the orders Windows can honor. Android's MANUAL is [CUSTOM]: the sequence the
 * reorder editor stores, which falls back to the installation order until the user picks one. [LANGUAGE] is a Windows
 * addition (Android groups by language instead of sorting by it), POPULARITY and LAST_USED stay unmapped because the
 * desktop library keeps neither a popularity count nor a per-source "last used" stamp.
 */
enum class DesktopSourceSort { DEFAULT, TITLE, LANGUAGE, CUSTOM }

/**
 * Android's `TriStateOption`, stored as the constant name so a preference file written on one platform reads on the
 * other: ENABLED means "always", DISABLED "never", ASK keeps the question for each adult work.
 */
enum class DesktopTriState { ENABLED, ASK, DISABLED }

/**
 * Android's `AdultContentFilterTarget` reduced to the surfaces Windows actually renders. A target hides 18+ works
 * from that shelf; the browse target hides 18+ *sources* in the rail, which is what the rail can decide. FEED is the
 * subscriptions timeline and UPDATES its tracked-with-new-chapters carousel — Android splits those over two screens,
 * Windows shows both on one page, so each target keeps hiding only its own half. Android's SUGGESTIONS and BOOKMARKS
 * have no Windows page yet and stay unmapped instead of pretending to hide something.
 */
enum class DesktopNsfwTarget { SOURCES, FAVOURITES, HISTORY, FEED, UPDATES }

/** Windows counterparts of Android's source settings, stored under their own keys in `desktop_background`. */
data class DesktopSourceSettings(
    val sort: DesktopSourceSort = DesktopSourceSort.DEFAULT,
    val grid: Boolean = true,
    val groupByLanguage: Boolean = false,
    /**
     * Android keeps the hand-picked sequence in each source row's `sort_key`; the desktop library has no source table,
     * so the reorder editor stores the names here instead. Names that are no longer installed are kept (a source can
     * come back after a reinstall) and simply match nothing while sorting.
     */
    val order: List<String> = emptyList(),
    val nsfwTargets: Set<DesktopNsfwTarget> = emptySet(),
    /** Android's global tag blacklist: taxonomy IDs, source aliases or `raw:` keys picked on the blacklist page. */
    val tagBlacklist: Set<String> = emptySet(),
    /** Android's `tags_warnings`: tint the chips of titles the shared warn-list calls extreme. */
    val tagsWarnings: Boolean = true,
    /** Android's `incognito_nsfw`: whether reading an adult work keeps the progress out of the history. */
    val incognitoForNsfw: DesktopTriState = DesktopTriState.ASK,
) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_SORT to SourcePreferenceValue.Text(sort.name),
        KEY_GRID to SourcePreferenceValue.Toggle(grid),
        KEY_GROUP_LANGUAGE to SourcePreferenceValue.Toggle(groupByLanguage),
        KEY_ORDER to SourcePreferenceValue.TextSet(LinkedHashSet(order)),
        KEY_NSFW_TARGETS to SourcePreferenceValue.TextSet(nsfwTargets.mapTo(LinkedHashSet()) { it.name }),
        KEY_TAG_BLACKLIST to SourcePreferenceValue.TextSet(LinkedHashSet(tagBlacklist)),
        KEY_TAGS_WARNINGS to SourcePreferenceValue.Toggle(tagsWarnings),
        KEY_INCOGNITO_NSFW to SourcePreferenceValue.Text(incognitoForNsfw.name),
    )

    /**
     * Android wraps the stored selection in `GlobalTagBlacklist` and hands it to each deriver; the matching rules are
     * shared, so Windows builds the same matcher from the same strings instead of copying the semantics.
     */
    fun tagBlacklistRules(): TagBlacklist =
        if (tagBlacklist.isEmpty()) TagBlacklist.Empty else TagTitleBlacklist(tagBlacklist)

    /** Whether a tag title earns the warning tint: the same shared list Android uses, unless the user turned it off. */
    fun isTagWarning(title: String): Boolean = tagsWarnings && TagWarnings.isWarned(title)

    /**
     * The rail order: Android's alphabetical and language orders, and the hand-picked sequence of the reorder editor.
     * The default keeps the installation order, which is also what [DesktopSourceSort.CUSTOM] falls back to while the
     * stored sequence names no installed source. `sortedBy` is stable, so unnamed sources keep the host's order.
     */
    fun sorted(sources: List<SourceListing>): List<SourceListing> = when (sort) {
        DesktopSourceSort.DEFAULT -> sources
        DesktopSourceSort.TITLE -> sources.sortedBy { it.displayName.lowercase() }
        DesktopSourceSort.LANGUAGE -> sources.sortedWith(
            compareBy({ it.source.locale.ifBlank { "zz" } }, { it.displayName.lowercase() }),
        )
        DesktopSourceSort.CUSTOM -> {
            val rank = order.withIndex().associate { (index, name) -> name to index }
            sources.sortedBy { rank[it.source.name] ?: Int.MAX_VALUE }
        }
    }

    /** Android groups the rail by language; a blank locale collects under one "multilingual" header. */
    fun groups(sources: List<SourceListing>): List<Pair<String, List<SourceListing>>> =
        if (!groupByLanguage) listOf("" to sources)
        else sources.groupBy { it.source.locale.ifBlank { "" } }.toList()

    fun hidesNsfw(target: DesktopNsfwTarget): Boolean = target in nsfwTargets

    /**
     * Android's `AppSettings.isIncognitoModeEnabled(isNsfw)`: the stored choice alone decides, so an adult work opens
     * incognito without a question while everything else always records its progress. Windows has no global
     * incognito switch (Android keeps it out of the settings screens), so only the adult rule applies here.
     */
    fun requiresIncognito(content: SourceContent): Boolean =
        content.isNsfw && incognitoForNsfw == DesktopTriState.ENABLED

    /** Whether opening [content] has to ask first, which is the Android default. */
    fun asksForIncognito(content: SourceContent): Boolean =
        content.isNsfw && incognitoForNsfw == DesktopTriState.ASK

    companion object {
        const val KEY_SORT = "sources_sort"
        const val KEY_GRID = "sources_grid"
        const val KEY_GROUP_LANGUAGE = "sources_group_language"

        /** The reorder editor's sequence; Android has no key for it because it writes `sort_key` per source row. */
        const val KEY_ORDER = "sources_order"

        const val KEY_NSFW_TARGETS = "sources_nsfw_targets"

        /** Android stores the same selection under `global_tag_blacklist`, so a shared backup reads either. */
        const val KEY_TAG_BLACKLIST = "global_tag_blacklist"

        /** Android's key for the warn-list tint, kept so the same backup keeps the same look. */
        const val KEY_TAGS_WARNINGS = "tags_warnings"

        /** Android's key for the adult-work incognito choice, same name and same stored constant names. */
        const val KEY_INCOGNITO_NSFW = "incognito_nsfw"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopSourceSettings(
            sort = DesktopSourceSort.entries.firstOrNull {
                it.name == (values[KEY_SORT] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopSourceSort.DEFAULT,
            grid = (values[KEY_GRID] as? SourcePreferenceValue.Toggle)?.value ?: true,
            groupByLanguage = (values[KEY_GROUP_LANGUAGE] as? SourcePreferenceValue.Toggle)?.value ?: false,
            order = (values[KEY_ORDER] as? SourcePreferenceValue.TextSet)?.values
                ?.filter(String::isNotBlank)
                ?.toList()
                .orEmpty(),
            nsfwTargets = (values[KEY_NSFW_TARGETS] as? SourcePreferenceValue.TextSet)?.values
                ?.mapNotNullTo(LinkedHashSet()) { name -> DesktopNsfwTarget.entries.firstOrNull { it.name == name } }
                .orEmpty(),
            tagBlacklist = (values[KEY_TAG_BLACKLIST] as? SourcePreferenceValue.TextSet)?.values
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty(),
            tagsWarnings = (values[KEY_TAGS_WARNINGS] as? SourcePreferenceValue.Toggle)?.value ?: true,
            incognitoForNsfw = DesktopTriState.entries.firstOrNull {
                it.name == (values[KEY_INCOGNITO_NSFW] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopTriState.ASK,
        )
    }
}

/** Android's `source_nsfw_overrides` / `source_sfw_overrides`, kept in the same store as `disabled_sources`. */
const val KEY_SOURCE_NSFW_OVERRIDES = "source_nsfw_overrides"
const val KEY_SOURCE_SFW_OVERRIDES = "source_sfw_overrides"

/** The stored adult-flag overrides, edited through the rule shared with Android (`SourceNsfwOverrideSet`). */
fun desktopSourceNsfwOverrides(values: Map<String, SourcePreferenceValue>): SourceNsfwOverrideSet =
    SourceNsfwOverrideSet(
        nsfw = (values[KEY_SOURCE_NSFW_OVERRIDES] as? SourcePreferenceValue.TextSet)?.values.orEmpty(),
        sfw = (values[KEY_SOURCE_SFW_OVERRIDES] as? SourcePreferenceValue.TextSet)?.values.orEmpty(),
    )

/**
 * Whether the source is adult. The host already folds each ecosystem's own metadata into the content type — an
 * extension that declares itself NSFW is listed as `HENTAI_*`, exactly what Android's `ContentSource.isNsfw()` falls
 * back to — and the user may force the flag either way from the source settings, so the override wins.
 */
fun SourceListing.isNsfwSource(overrides: SourceNsfwOverrideSet = SourceNsfwOverrideSet()): Boolean =
    overrides.resolve(source.name) ?: source.contentType.startsWith(CONTENT_TYPE_HENTAI_PREFIX)

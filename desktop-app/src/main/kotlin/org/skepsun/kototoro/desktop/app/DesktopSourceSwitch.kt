package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.extensions.SelectedSourcesDeletePlan
import org.skepsun.kototoro.core.extensions.SelectedSourcesNsfwAction
import org.skepsun.kototoro.core.extensions.SourceDeletionCandidate
import org.skepsun.kototoro.core.extensions.SourceNsfwOverrideSet
import org.skepsun.kototoro.core.extensions.resolveSelectedSourcesNsfwAction
import org.skepsun.kototoro.core.extensions.selectedSourcesDeletePlan
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.desktop.runtime.isNsfwSource

/** Android's `UnifiedEnabledFilter`: which side of the per-source switch a row has to be on to be listed. */
enum class DesktopSourceEnabledFilter { ALL, ENABLED, DISABLED }

/**
 * The source-switch dialog's visible rows: Android's search (title or source name, case-insensitive) and its enabled
 * filter, combined with AND and kept in the host's own order. The bulk enable/disable buttons act on exactly this
 * list, which is what Android's "enable/disable all in the current filter" confirmation counts.
 */
fun filterSourceSwitchEntries(
    sources: List<SourceListing>,
    query: String,
    filter: DesktopSourceEnabledFilter,
    disabled: Set<String>,
): List<SourceListing> {
    val needle = query.trim()
    if (needle.isEmpty() && filter == DesktopSourceEnabledFilter.ALL) return sources
    return sources.filter { listing ->
        val matchesQuery = needle.isEmpty() ||
            listing.displayName.contains(needle, ignoreCase = true) ||
            listing.source.name.contains(needle, ignoreCase = true)
        if (!matchesQuery) return@filter false
        when (filter) {
            DesktopSourceEnabledFilter.ALL -> true
            DesktopSourceEnabledFilter.ENABLED -> listing.source.name !in disabled
            DesktopSourceEnabledFilter.DISABLED -> listing.source.name in disabled
        }
    }
}

/**
 * Android's selection state, which both hosts keep outside the model: a tapped source joins or leaves the set, and an
 * empty set is what ends selection mode (the bar disappears and the rows are switches again).
 */
fun toggleSourceSelection(selected: Set<String>, sourceName: String): Set<String> =
    if (sourceName in selected) selected - sourceName else selected + sourceName

/**
 * Android's 全选 chip, which reads 取消全选 once the whole visible list is in the selection. Only the rows the search
 * and the enabled filter left on screen take part, exactly like the bulk enable/disable pair above them.
 */
fun toggleVisibleSourceSelection(selected: Set<String>, entries: List<SourceListing>): Set<String> {
    val visible = entries.mapTo(LinkedHashSet(entries.size)) { it.source.name }
    if (visible.isEmpty()) return selected
    return if (visible.all { it in selected }) selected - visible else selected + visible
}

/** Whether the 全选 chip reads 取消全选: an empty list is not "all selected". */
fun everyVisibleSourceSelected(selected: Set<String>, entries: List<SourceListing>): Boolean =
    entries.isNotEmpty() && entries.all { it.source.name in selected }

/**
 * Android's NSFW chip: one button that means 标记为 NSFW / 取消 NSFW / 设置 NSFW depending on the selection. The counts
 * come from the same effective flag the rail and the adult-content targets use, so a source the user already forced
 * one way is counted on that side rather than by its content type.
 */
fun selectedSourcesNsfwAction(
    selected: Set<String>,
    sources: List<SourceListing>,
    overrides: SourceNsfwOverrideSet,
): SelectedSourcesNsfwAction {
    val chosen = sources.filter { it.source.name in selected }
    return resolveSelectedSourcesNsfwAction(chosen.count { it.isNsfwSource(overrides) }, chosen.size)
}

/** Which Android string the chip shows for [action]; NONE never renders, so it borrows the mark label. */
fun selectedSourcesNsfwLabel(action: SelectedSourcesNsfwAction): String = when (action) {
    SelectedSourcesNsfwAction.SET_NSFW -> "unified_sources_mark_nsfw"
    SelectedSourcesNsfwAction.SET_SFW -> "unified_sources_unmark_nsfw"
    SelectedSourcesNsfwAction.CHOOSE -> "unified_sources_set_nsfw"
    SelectedSourcesNsfwAction.NONE -> "unified_sources_mark_nsfw"
}

/**
 * Android's `UnifiedSelectedSourceDeletePlan` on Windows: each installed package declares the sources it provides, so a
 * selection folds into the packages to uninstall. A parser plugin is a single-file JAR — deleting one because one of
 * its sources was selected would take the whole plugin, which is why Android only reports those, and so does this.
 */
fun sourceDeletePlan(
    selected: Set<String>,
    entries: List<DesktopInstalledEntry>,
): SelectedSourcesDeletePlan = selectedSourcesDeletePlan(
    selectedSourceNames = selected,
    candidates = entries.flatMap { entry ->
        entry.sourceNames.map { name ->
            SourceDeletionCandidate(
                sourceName = name,
                packageId = entry.id,
                packageName = entry.label,
                uninstallable = entry.kind != DesktopInstalledKind.PARSER,
            )
        }
    },
)


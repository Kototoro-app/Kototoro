package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.extensions.resolveJarPriorityOrder
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.ui.compose.ReorderEditorDialog
import org.skepsun.kototoro.core.ui.compose.ReorderItem
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsContent
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsContentState
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsTitles
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSort
import org.skepsun.kototoro.desktop.runtime.DesktopTriState

/**
 * Desktop adapter for the shared source settings page. Priority and adult-filter editors keep their original
 * immediate commits; navigation, runtime-specific policies and persistence stay in the host.
 */
@Composable
internal fun DesktopSourceSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.sourceSettings
    val active = !state.busy
    var dialog by remember { mutableStateOf<SourceSettingsDialog?>(null) }
    fun update(value: DesktopSourceSettings) = controller.sourceSettings(value)
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SourcesSettingsContent(
            state = SourcesSettingsContentState(
                sortOrder = settings.sort.name,
                customSort = settings.sort == DesktopSourceSort.CUSTOM,
                gridMode = settings.grid,
                groupByLanguage = settings.groupByLanguage,
                showBrokenSources = state.showBrokenSources,
                installBehaviorSummary = state.extensionInstallPolicies.policySummary(),
                incognitoMode = settings.incognitoForNsfw.name,
                blacklistedTagCount = settings.tagBlacklist.size,
                tagsWarnings = settings.tagsWarnings,
            ),
            titles = SourcesSettingsTitles(desktopText("sources.overview"), desktopText("sources.remote"),
                desktopText("sources.adult"), desktopText("sources.tags")),
            sortOptions = DesktopSourceSort.entries.map { PreferenceChoice(it.name, it.title) },
            incognitoOptions = DesktopTriState.entries.map { PreferenceChoice(it.name, it.title) },
            onSortChange = { update(settings.copy(sort = DesktopSourceSort.valueOf(it))) },
            onGridChange = { update(settings.copy(grid = it)) },
            onGroupByLanguageChange = { update(settings.copy(groupByLanguage = it)) },
            onReorderClick = { dialog = SourceSettingsDialog.REORDER },
            onWizardClick = { controller.showSetupWizard() },
            onShowBrokenChange = { controller.setShowBrokenSources(it) },
            onInstallBehaviorClick = { dialog = SourceSettingsDialog.INSTALL_BEHAVIOR },
            onIncognitoChange = { update(settings.copy(incognitoForNsfw = DesktopTriState.valueOf(it))) },
            onBlacklistClick = { controller.showTagBlacklist() },
            onTagsWarningsChange = { controller.setTagsWarnings(it) },
            enabled = active,
            reorderSummary = desktopText("sources.reorderSummary"),
            wizardSummary = desktopText("sources.wizardSummary"),
            jarPriorityEditor = {
                SettingsValueRow(AndroidStrings["jar_priority_order_title"],
                    AndroidStrings["jar_priority_order_summary"],
                    state.jarPriorityOrder.resolvedPluginNames(state).takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: AndroidStrings["not_specified"],
                    active, { dialog = SourceSettingsDialog.JAR_PRIORITY }, testTag = "source-setting-jar-priority")
                SettingsRowDivider()
                SettingsInfoRow(desktopText("sources.jarPriorityNote"), null)
            },
            adultFilterEditor = {
                SettingsValueRow(desktopText("sources.nsfwFilter"), desktopText("sources.nsfwFilterSummary"),
                    settings.nsfwTargets.mapTo(sortedSetOf()) { it.title }.showAsList(), active,
                    { dialog = SourceSettingsDialog.NSFW_TARGETS }, testTag = "source-setting-nsfw")
            },
            adultNote = desktopText("sources.adultNote"),
            moreNote = desktopText("sources.tagBlacklistNote"),
        )
    }
    when (dialog) {
        SourceSettingsDialog.JAR_PRIORITY -> DesktopJarPriorityDialog(state = state, enabled = active,
            onOrder = { controller.setJarPriorityOrder(it) }, onDismiss = { dialog = null })
        SourceSettingsDialog.REORDER -> DesktopSourceOrderDialog(
            sources = settings.sorted(state.enabledSources),
            enabled = active,
            onSave = { names ->
                dialog = null
                controller.saveSourceOrder(names)
            },
            onDismiss = { dialog = null },
        )
        SourceSettingsDialog.INSTALL_BEHAVIOR -> DesktopInstallBehaviorDialog(
            policies = state.extensionInstallPolicies,
            enabled = active,
            onPolicy = { typeName, policy -> controller.setExtensionInstallPolicy(typeName, policy) },
            onAllPolicies = { controller.setAllExtensionInstallPolicies(it) },
            onDismiss = { dialog = null },
        )
        SourceSettingsDialog.NSFW_TARGETS -> DesktopMultiChoiceSettingsDialog(
            title = desktopText("sources.nsfwFilter"),
            summary = desktopText("sources.nsfwFilterSummary"),
            bodyTag = "source-nsfw-dialog",
            optionTagPrefix = "source-nsfw",
            options = DesktopNsfwTarget.entries.map { it.title to it.name },
            selected = settings.nsfwTargets.mapTo(LinkedHashSet()) { it.name },
            enabled = active,
            // Desktop keeps immediate writes; Android confirms its private draft.
            onValueChange = { names ->
                update(settings.copy(nsfwTargets = DesktopNsfwTarget.entries.filterTo(LinkedHashSet()) { it.name in names }))
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class SourceSettingsDialog { JAR_PRIORITY, NSFW_TARGETS, INSTALL_BEHAVIOR, REORDER }

/**
 * The parser plugins that `jar_priority_order` ranks, in that order; plugins the value does not name come last. A
 * session that has not listed its extensions yet only knows the stored names, which are the plugin ids anyway.
 */
private fun String.resolvedPluginNames(state: DesktopAppState): List<String> {
    val installed = state.installedEntries.filter { it.kind == DesktopInstalledKind.PARSER }.map { it.id }
    return if (installed.isEmpty()) parseJarPriorityNames() else resolveJarPriorityOrder(installed, this)
}

/**
 * Android's reorder preference for the deduplication order. The list is the installed parser plugins; each move
 * stores immediately like the category manager does, because [org.skepsun.kototoro.parserhost.ParserPluginRegistry]
 * applies the order when it opens — the row's note says so instead of promising a live re-rank.
 */
@Composable
private fun DesktopJarPriorityDialog(state: DesktopAppState, enabled: Boolean, onOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit) {
    val order = state.jarPriorityOrder.resolvedPluginNames(state)
    ReorderEditorDialog(
        items = order.map { ReorderItem(it, it) },
        onDismissRequest = onDismiss,
        onSave = { onOrder(it) },
        enabled = enabled,
        saveOnMove = true,
        title = AndroidStrings["jar_priority_order_title"],
        summary = AndroidStrings["jar_priority_order_summary"],
        footerText = desktopText("sources.jarPriorityNote"),
        emptyText = desktopText("extensions.installedEmpty"),
        tagPrefix = "jar-priority",
    )
}

/**
 * Android's `ReorderDialog` for the source rail. As on Android, moving never writes: the rows are a local draft and
 * only "保存" stores the sequence (which also switches the sort order to it, exactly like `saveSourceOrder` does).
 * Android drags a handle and locks each move to the item's group because its editor draws the pinned/language headers;
 * this list is flat and a mouse host has no long-press, so every row carries the two moves as buttons — the same shape
 * the plugin priority dialog uses — and 按语言分组 re-groups the rail afterwards, leaving the stored sequence in charge
 * of the order inside each language.
 */
@Composable
private fun DesktopSourceOrderDialog(
    sources: List<SourceListing>,
    enabled: Boolean,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    ReorderEditorDialog(
        items = sources.map { ReorderItem(it.source.name, it.displayName) },
        onDismissRequest = onDismiss,
        onSave = { onSave(it) },
        enabled = enabled,
        showMoveButtons = true,
        summary = desktopText("sources.reorderSummary"),
        emptyText = desktopText("sources.reorderEmpty"),
        tagPrefix = "source-order",
    )
}

/** Same rendering rule as the recommendations page: nothing selected reads as no value at all. */
private fun Set<String>.showAsList(): String? =
    takeIf { it.isNotEmpty() }?.sorted()?.joinToString(", ")

private val DesktopSourceSort.title: String
    get() = when (this) {
        DesktopSourceSort.DEFAULT -> desktopText("sources.sort.default")
        DesktopSourceSort.TITLE -> desktopText("sources.sort.title")
        DesktopSourceSort.LANGUAGE -> desktopText("sources.sort.language")
        DesktopSourceSort.CUSTOM -> desktopText("sources.sort.custom")
    }

private val DesktopNsfwTarget.title: String
    get() = when (this) {
        DesktopNsfwTarget.SOURCES -> desktopText("sources.nsfw.sources")
        DesktopNsfwTarget.FAVOURITES -> desktopText("sources.nsfw.favourites")
        DesktopNsfwTarget.HISTORY -> desktopText("sources.nsfw.history")
        DesktopNsfwTarget.FEED -> desktopText("sources.nsfw.feed")
        DesktopNsfwTarget.UPDATES -> desktopText("sources.nsfw.updates")
        DesktopNsfwTarget.BOOKMARKS -> desktopText("sources.nsfw.bookmarks")
    }

/** Android's three options in its `TriStateOption` order, worded as the question about adult works. */
private val DesktopTriState.title: String
    get() = when (this) {
        DesktopTriState.ENABLED -> AndroidStrings["enable"]
        DesktopTriState.ASK -> AndroidStrings["ask_every_time"]
        DesktopTriState.DISABLED -> AndroidStrings["disable"]
    }

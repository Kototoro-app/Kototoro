package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.extensions.resolveJarPriorityOrder
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSort
import org.skepsun.kototoro.desktop.runtime.DesktopTriState

/**
 * Android's `SourcesSettingsScreen`. Windows kept all of it inside the browse rail — the rail simply followed the
 * installation order and always listed every source — so order, layout and adult filtering had no home of their own.
 * The rows and dialogs are the same §135-§140 vocabulary; per-source preferences keep their own page.
 */
@Composable
internal fun DesktopSourceSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.sourceSettings
    val active = !state.busy
    var dialog by remember { mutableStateOf<SourceSettingsDialog?>(null) }
    fun update(value: DesktopSourceSettings) = controller.sourceSettings(value)
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active,
            modifier = Modifier.testTag("source-settings-back")) { Text(desktopText("common.back")) }
        SettingsSectionHeader(desktopText("sources.overview"))
        SettingsGroup {
            SettingsValueRow(desktopText("sources.sort"), desktopText("sources.sortSummary"), settings.sort.title,
                active, { dialog = SourceSettingsDialog.SORT }, testTag = "source-setting-sort")
            SettingsRowDivider()
            SettingsSwitchRow(desktopText("sources.grid"), desktopText("sources.gridSummary"), settings.grid, active,
                { update(settings.copy(grid = it)) }, testTag = "source-setting-grid")
            SettingsRowDivider()
            SettingsSwitchRow(desktopText("sources.groupLanguage"), desktopText("sources.groupLanguageSummary"),
                settings.groupByLanguage, active, { update(settings.copy(groupByLanguage = it)) },
                testTag = "source-setting-group-language")
        }
        SettingsSectionHeader(desktopText("sources.remote"))
        SettingsGroup {
            // Android's `jar_priority_order` (a reorder preference): which plugin serves a source two plugins share.
            SettingsValueRow(AndroidStrings["jar_priority_order_title"],
                AndroidStrings["jar_priority_order_summary"],
                state.jarPriorityOrder.resolvedPluginNames(state).takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: AndroidStrings["not_specified"],
                active, { dialog = SourceSettingsDialog.JAR_PRIORITY }, testTag = "source-setting-jar-priority")
            SettingsRowDivider()
            // The restart note belongs to the priority order above, so it stays right under it.
            SettingsInfoRow(desktopText("sources.jarPriorityNote"), null)
            SettingsRowDivider()
            // Android's `show_broken_sources`: a `@Broken` parser stays installed, this decides if lists offer it.
            // Unlike the order above it applies at once, so it sits after the restart note rather than before it.
            SettingsSwitchRow(AndroidStrings["show_broken_sources"],
                AndroidStrings["show_broken_sources_summary"], state.showBrokenSources, active,
                { controller.setShowBrokenSources(it) }, testTag = "source-setting-show-broken")
        }
        SettingsSectionHeader(desktopText("sources.tags"))
        SettingsGroup {
            // Android's `GlobalTagBlacklistScreen`, opened as its own page instead of a preference dialog.
            SettingsValueRow(desktopText("sources.tagBlacklist"), desktopText("sources.tagBlacklistSummary"),
                settings.tagBlacklist.size.takeIf { it > 0 }?.let { desktopText("sources.tagBlacklistCount", it) },
                active, { controller.showTagBlacklist() }, testTag = "source-setting-tag-blacklist")
            SettingsRowDivider()
            // Android's `tags_warnings`: the same shared warn-list, only the details chip tint follows the switch.
            SettingsSwitchRow(desktopText("sources.tagsWarnings"), desktopText("sources.tagsWarningsSummary"),
                settings.tagsWarnings, active, { controller.setTagsWarnings(it) },
                testTag = "source-setting-tags-warnings")
            SettingsRowDivider()
            SettingsInfoRow(desktopText("sources.tagBlacklistNote"), null)
        }
        SettingsSectionHeader(desktopText("sources.adult"))
        SettingsGroup {
            SettingsValueRow(desktopText("sources.nsfwFilter"), desktopText("sources.nsfwFilterSummary"),
                settings.nsfwTargets.mapTo(sortedSetOf()) { it.title }.showAsList(), active,
                { dialog = SourceSettingsDialog.NSFW_TARGETS }, testTag = "source-setting-nsfw")
            SettingsRowDivider()
            // Android's `incognito_nsfw`: what reading an adult work does to the history.
            SettingsValueRow(AndroidStrings["incognito_for_nsfw"], AndroidStrings["incognito_mode_hint"],
                settings.incognitoForNsfw.title, active, { dialog = SourceSettingsDialog.INCOGNITO_NSFW },
                testTag = "source-setting-incognito-nsfw")
            SettingsRowDivider()
            SettingsInfoRow(desktopText("sources.adultNote"), null)
        }
    }
    when (dialog) {
        SourceSettingsDialog.SORT -> DesktopChoiceSettingsDialog(
            title = desktopText("sources.sort"),
            summary = desktopText("sources.sortSummary"),
            bodyTag = "source-settings-dialog",
            optionTagPrefix = "source-sort",
            options = DesktopSourceSort.entries.map { it.title to it.name },
            selected = settings.sort.name,
            enabled = active,
            onPick = { name ->
                dialog = null
                DesktopSourceSort.entries.firstOrNull { it.name == name }?.let { update(settings.copy(sort = it)) }
            },
            onDismiss = { dialog = null },
        )
        SourceSettingsDialog.INCOGNITO_NSFW -> DesktopChoiceSettingsDialog(
            title = AndroidStrings["incognito_for_nsfw"],
            summary = AndroidStrings["incognito_mode_hint"],
            bodyTag = "source-incognito-dialog",
            optionTagPrefix = "source-incognito",
            options = DesktopTriState.entries.map { it.title to it.name },
            selected = settings.incognitoForNsfw.name,
            enabled = active,
            onPick = { name ->
                dialog = null
                DesktopTriState.entries.firstOrNull { it.name == name }
                    ?.let { update(settings.copy(incognitoForNsfw = it)) }
            },
            onDismiss = { dialog = null },
        )
        SourceSettingsDialog.JAR_PRIORITY -> DesktopJarPriorityDialog(state = state, enabled = active,
            onOrder = { controller.setJarPriorityOrder(it) }, onDismiss = { dialog = null })
        SourceSettingsDialog.NSFW_TARGETS -> DesktopMultiChoiceSettingsDialog(
            title = desktopText("sources.nsfwFilter"),
            summary = desktopText("sources.nsfwFilterSummary"),
            bodyTag = "source-nsfw-dialog",
            optionTagPrefix = "source-nsfw",
            options = DesktopNsfwTarget.entries.map { it.title to it.name },
            selected = settings.nsfwTargets.mapTo(LinkedHashSet()) { it.name },
            enabled = active,
            // Android keeps the target set live, so a click stores immediately instead of on a confirm button.
            onToggle = { name, checked ->
                val target = DesktopNsfwTarget.entries.firstOrNull { it.name == name } ?: return@DesktopMultiChoiceSettingsDialog
                update(settings.copy(nsfwTargets = if (checked) settings.nsfwTargets - target
                else settings.nsfwTargets + target))
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class SourceSettingsDialog { SORT, JAR_PRIORITY, NSFW_TARGETS, INCOGNITO_NSFW }

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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).testTag("jar-priority-dialog"),
        title = { Text(AndroidStrings["jar_priority_order_title"]) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(AndroidStrings["jar_priority_order_summary"],
                    style = MaterialTheme.typography.bodySmall)
                if (order.isEmpty()) {
                    Text(desktopText("extensions.installedEmpty"),
                        modifier = Modifier.testTag("jar-priority-empty"),
                        style = MaterialTheme.typography.bodySmall)
                }
                order.forEachIndexed { index, name ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().testTag("jar-priority:$name")) {
                        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp)
                        TextButton({ onOrder(order.moved(from = index, to = index - 1)) },
                            enabled = enabled && index > 0, modifier = Modifier.testTag("jar-priority-up:$name")) {
                            Text(desktopText("library.categoryUp"))
                        }
                        TextButton({ onOrder(order.moved(from = index, to = index + 1)) },
                            enabled = enabled && index < order.lastIndex,
                            modifier = Modifier.testTag("jar-priority-down:$name")) {
                            Text(desktopText("library.categoryDown"))
                        }
                    }
                }
                Text(desktopText("sources.jarPriorityNote"), modifier = Modifier.testTag("jar-priority-note"),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("jar-priority-close")) {
                Text(desktopText("common.close"))
            }
        },
    )
}

private fun List<String>.moved(from: Int, to: Int): List<String> =
    toMutableList().apply { add(to, removeAt(from)) }

/** Same rendering rule as the recommendations page: nothing selected reads as no value at all. */
private fun Set<String>.showAsList(): String? =
    takeIf { it.isNotEmpty() }?.sorted()?.joinToString(", ")

private val DesktopSourceSort.title: String
    get() = when (this) {
        DesktopSourceSort.DEFAULT -> desktopText("sources.sort.default")
        DesktopSourceSort.TITLE -> desktopText("sources.sort.title")
        DesktopSourceSort.LANGUAGE -> desktopText("sources.sort.language")
    }

private val DesktopNsfwTarget.title: String
    get() = when (this) {
        DesktopNsfwTarget.SOURCES -> desktopText("sources.nsfw.sources")
        DesktopNsfwTarget.FAVOURITES -> desktopText("sources.nsfw.favourites")
        DesktopNsfwTarget.HISTORY -> desktopText("sources.nsfw.history")
        DesktopNsfwTarget.FEED -> desktopText("sources.nsfw.feed")
        DesktopNsfwTarget.UPDATES -> desktopText("sources.nsfw.updates")
    }

/** Android's three options in its `TriStateOption` order, worded as the question about adult works. */
private val DesktopTriState.title: String
    get() = when (this) {
        DesktopTriState.ENABLED -> AndroidStrings["enable"]
        DesktopTriState.ASK -> AndroidStrings["ask_every_time"]
        DesktopTriState.DISABLED -> AndroidStrings["disable"]
    }

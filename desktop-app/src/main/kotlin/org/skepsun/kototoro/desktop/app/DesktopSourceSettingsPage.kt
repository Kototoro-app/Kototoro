package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSort

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

private enum class SourceSettingsDialog { SORT, NSFW_TARGETS }

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

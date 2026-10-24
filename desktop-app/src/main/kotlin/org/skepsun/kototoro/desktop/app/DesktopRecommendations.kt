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
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.suggestions.domain.parseSuggestionTags

/**
 * Android's `SuggestionsSettingsScreen`, which the Windows host had squashed into the "more" page: a switch card, two
 * inline tag fields and one filter-chip row per installed source — the flat per-source layout Android deliberately
 * avoids. The rules stay the same and read the same `DesktopSuggestionSettings`, but each group is now its own
 * section, and Android's own source picker (search, content-type and source-type filters, pending selection) is what
 * the two source rows open, so a long source list is no longer a wall of check boxes.
 */
@Composable
internal fun DesktopRecommendations(controller: DesktopController, state: DesktopAppState) {
    val settings = state.suggestionSettings
    val active = !state.busy
    // Android disables the dependent preferences while recommendations are off.
    val enabled = active && settings.enabled
    // Android builds suggestions from the enabled sources only, so a switched-off source is not offered here either.
    val sources = state.enabledSources
    val options = remember(sources) { suggestionSourceOptions(sources) }
    var dialog by remember { mutableStateOf<RecommendationDialog?>(null) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active) { Text(desktopText("common.back")) }
        SettingsSectionHeader(desktopText("settings.suggestions"))
        SettingsGroup {
            SettingsSwitchRow(desktopText("settings.suggestionsEnabled"),
                desktopText("settings.suggestionsEnabledDetail"), settings.enabled, active,
                { controller.suggestionSettings(settings.copy(enabled = it)) }, testTag = "settings-suggestions-enabled")
            SettingsRowDivider()
            SettingsSwitchRow(desktopText("settings.suggestionsNsfw"), null, settings.excludeNsfw, enabled,
                { controller.suggestionSettings(settings.copy(excludeNsfw = it)) }, testTag = "settings-suggestions-nsfw")
            SettingsRowDivider()
            SettingsInfoRow(desktopText("settings.suggestionsInfo"), null)
        }
        SettingsSectionHeader(desktopText("settings.suggestionsTags"))
        SettingsGroup {
            SettingsValueRow(desktopText("settings.suggestionsExcludeTags"), desktopText("settings.tagSeparator"),
                settings.tagBlacklist.showAsList(), enabled, { dialog = RecommendationDialog.EXCLUDED_TAGS },
                testTag = "settings-suggestions-exclude-tags")
            SettingsRowDivider()
            SettingsValueRow(desktopText("settings.suggestionsPreferredTags"), desktopText("settings.tagSeparator"),
                settings.tagWhitelist.showAsList(), enabled, { dialog = RecommendationDialog.PREFERRED_TAGS },
                testTag = "settings-suggestions-preferred-tags")
        }
        SettingsSectionHeader(desktopText("settings.suggestionsSources"))
        SettingsGroup {
            // Android shows the chosen names in the preference summary (three, then "+N"), not in a value label.
            val preferredEmpty = AndroidStrings["suggestions_preferred_sources_summary"]
            val excludedEmpty = AndroidStrings["suggestions_excluded_sources_summary"]
            SettingsValueRow(desktopText("settings.suggestionsPreferredSources"),
                suggestionSourceSummary(settings.preferredSources, options, preferredEmpty),
                null, enabled, { dialog = RecommendationDialog.PREFERRED_SOURCES },
                testTag = "settings-suggestions-prefer-sources")
            SettingsRowDivider()
            SettingsValueRow(desktopText("settings.suggestionsExcludedSources"),
                suggestionSourceSummary(settings.excludedSources, options, excludedEmpty),
                null, enabled, { dialog = RecommendationDialog.EXCLUDED_SOURCES },
                testTag = "settings-suggestions-exclude-sources")
        }
    }
    val open = dialog ?: return
    when (open) {
        RecommendationDialog.PREFERRED_SOURCES, RecommendationDialog.EXCLUDED_SOURCES -> {
            val preferred = open == RecommendationDialog.PREFERRED_SOURCES
            DesktopSuggestionSourcesDialog(
                title = desktopText(if (preferred) "settings.suggestionsPreferredSources"
                    else "settings.suggestionsExcludedSources"),
                options = options,
                selectedIds = if (preferred) settings.preferredSources else settings.excludedSources,
                bodyTag = "settings-suggestions-sources-dialog",
                optionTagPrefix = if (preferred) "settings-suggestions-prefer" else "settings-suggestions-exclude",
                onConfirm = { picked ->
                    // Android keeps the two lists disjoint: preferring a source drops it from the excluded one.
                    controller.suggestionSettings(if (preferred) {
                        settings.copy(preferredSources = picked, excludedSources = settings.excludedSources - picked)
                    } else {
                        settings.copy(excludedSources = picked, preferredSources = settings.preferredSources - picked)
                    })
                    dialog = null
                }, onDismiss = { dialog = null })
        }
        // The tag rows edit one comma separated list each, which the shared rule parses back into a set.
        RecommendationDialog.PREFERRED_TAGS, RecommendationDialog.EXCLUDED_TAGS -> {
            val preferred = open == RecommendationDialog.PREFERRED_TAGS
            val key = if (preferred) "settings.suggestionsPreferredTags" else "settings.suggestionsExcludeTags"
            val tag = if (preferred) "settings-suggestions-preferred-tags" else "settings-suggestions-exclude-tags"
            DesktopTextSettingsDialog(
                title = desktopText(key), summary = desktopText("settings.tagSeparator"),
                bodyTag = "settings-suggestions-tags-dialog", fieldTag = "$tag-field", saveTag = "$tag-save",
                initial = (if (preferred) settings.tagWhitelist else settings.tagBlacklist).joinToString(", "),
                enabled = enabled, onSave = { text ->
                    val tags = parseSuggestionTags(text)
                    controller.suggestionSettings(if (preferred) settings.copy(tagWhitelist = tags)
                        else settings.copy(tagBlacklist = tags))
                    true
                }, onDismiss = { dialog = null })
        }
    }
}

/** Which settings row's dialog is open; tag rows share one text dialog and source rows Android's source picker. */
private enum class RecommendationDialog { EXCLUDED_TAGS, PREFERRED_TAGS, PREFERRED_SOURCES, EXCLUDED_SOURCES }

/** A row hides the value entirely when nothing is set, like Android's empty preference summary. */
private fun Set<String>.showAsList(label: (String) -> String = { it }): String? =
    takeIf { it.isNotEmpty() }?.let { values -> values.map(label).sorted().joinToString(", ") }

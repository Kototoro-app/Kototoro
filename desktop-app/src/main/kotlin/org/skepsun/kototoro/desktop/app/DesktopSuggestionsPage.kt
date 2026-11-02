package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.page.PageSummaryAction
import org.skepsun.kototoro.core.ui.page.PageSummaryHeader
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionFilter
import org.skepsun.kototoro.desktop.runtime.desktopSuggestedContents
import org.skepsun.kototoro.desktop.runtime.desktopSuggestionFilters

/**
 * Android's suggestions destination (`NavItem.SUGGESTIONS` → `SuggestionsTopLevelRouteContent`): the stored
 * recommendations as one grid, with the header card's refresh action and the quick-filter chips above it.
 *
 * The works are the same rows the home rail draws ([org.skepsun.kototoro.desktop.runtime.DesktopSuggestions] stores
 * them in the shared `suggestions` table), and the chips come from [desktopSuggestionFilters] — Android's five most
 * frequent tags, its two adult macros while adult works are not excluded, and its three most frequent sources.
 *
 * Deviations from the phone, both because the machinery does not exist on Windows: the list mode is the shelf's
 * single `library_list_mode` choice instead of Android's separate suggestions scope, and a switched-off
 * recommendations feature is asked for on the page itself (the destination still exists, like Android's tab).
 */
@Composable
internal fun DesktopSuggestionsPage(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val settings = state.suggestionSettings
    val items = state.suggestions
    val chips = remember(items, settings.excludeNsfw) {
        desktopSuggestionFilters(items, includeAdultChips = !settings.excludeNsfw)
    }
    val filters = state.suggestionFilters
    val visible = remember(items, filters) { desktopSuggestedContents(items, filters) }
    Column(Modifier.fillMaxSize().testTag("suggestions-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Android's suggestions header card (same accent tile, wash and pills) instead of a bare title row.
        PageSummaryHeader(
            title = AndroidStrings["suggestions_header_title"],
            subtitle = AndroidStrings["suggestions_header_subtitle"],
            icon = painterResource("icons/ic_explore_normal.svg"),
            accent = MaterialTheme.colorScheme.tertiary,
            horizontalPadding = 0.dp,
            modifier = Modifier.testTag("suggestions-summary"),
        ) {
            PageSummaryAction(
                text = desktopText("settings.suggestions"),
                icon = painterResource("icons/ic_settings.svg"),
                accent = MaterialTheme.colorScheme.tertiary,
                enabled = enabled,
                onClick = { controller.showRecommendations() },
                modifier = Modifier.testTag("suggestions-settings"),
            )
            PageSummaryAction(
                text = AndroidStrings["suggestions_header_refresh"],
                icon = painterResource("icons/ic_sync.svg"),
                emphasized = true,
                accent = MaterialTheme.colorScheme.tertiary,
                enabled = enabled && settings.enabled && !state.suggestionsGenerating,
                onClick = { controller.refreshSuggestions() },
                modifier = Modifier.testTag("suggestions-refresh"),
            )
        }
        if (!settings.enabled) {
            // Android's tab keeps leading to a page that asks for the feature instead of hiding the destination.
            SuggestionsNotice(
                text = desktopText("home.suggestionsOffDetail"),
                tag = "suggestions-disabled",
                action = desktopText("home.enableSuggestions"),
                actionTag = "suggestions-enable",
                enabled = enabled,
                onAction = { controller.enableSuggestions() },
            )
        } else if (state.suggestionsGenerating) {
            Column(Modifier.fillMaxWidth().testTag("suggestions-generating"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(desktopText("message.generatingSuggestions"), color = Muted, fontSize = 13.sp)
            }
        }
        if (settings.enabled && items.isEmpty()) {
            SuggestionsNotice(
                text = desktopText("home.suggestionsDetail"),
                tag = "suggestions-empty",
                action = desktopText("home.generateSuggestions"),
                actionTag = "suggestions-generate",
                enabled = enabled && !state.suggestionsGenerating,
                onAction = { controller.refreshSuggestions() },
            )
        } else if (items.isNotEmpty()) {
            if (chips.isNotEmpty()) SuggestionChips(chips, filters, enabled, controller::toggleSuggestionFilter)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ContentGrid(
                    contents = visible,
                    enabled = enabled,
                    covers = controller.session.covers,
                    onOpen = controller::details,
                    listMode = state.libraryDisplay.listMode,
                )
            }
        }
    }
}

/** Android's `ContentListQuickFilter` chips: several selected chips narrow together, a chip tapped again drops out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(
    chips: List<DesktopSuggestionFilter>,
    selected: Set<DesktopSuggestionFilter>,
    enabled: Boolean,
    onToggle: (DesktopSuggestionFilter) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        chips.forEach { chip ->
            FilterChip(
                selected = chip in selected,
                onClick = { onToggle(chip) },
                enabled = enabled,
                label = { Text(chip.label()) },
                modifier = Modifier.testTag("suggestion-chip:${chip.key()}"),
            )
        }
    }
}

/**
 * Android's header card (and the shelves' own empty states) as one filled row: what this page is for, and the one
 * action that makes it produce something.
 */
@Composable
private fun SuggestionsNotice(
    text: String,
    tag: String,
    action: String,
    actionTag: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onAction, enabled = enabled, modifier = Modifier.testTag(actionTag)) { Text(action) }
        }
    }
}

/** What the chip says: Android labels tags by their title, sources by their name, the macros by its own strings. */
private fun DesktopSuggestionFilter.label(): String = when (this) {
    is DesktopSuggestionFilter.Tag -> title
    is DesktopSuggestionFilter.Source -> name
    DesktopSuggestionFilter.Adult -> AndroidStrings["nsfw"]
    DesktopSuggestionFilter.Safe -> AndroidStrings["sfw"]
}

/** A stable test tag per chip, so a probe can pick exactly the chip Android's SQL condition would match. */
private fun DesktopSuggestionFilter.key(): String = when (this) {
    is DesktopSuggestionFilter.Tag -> "tag:$title"
    is DesktopSuggestionFilter.Source -> "source:$name"
    DesktopSuggestionFilter.Adult -> "nsfw"
    DesktopSuggestionFilter.Safe -> "sfw"
}

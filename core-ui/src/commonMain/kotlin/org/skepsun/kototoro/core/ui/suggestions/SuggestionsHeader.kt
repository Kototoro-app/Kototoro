package org.skepsun.kototoro.core.ui.suggestions

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.buttons.FilledTonalIconButton
import org.skepsun.kototoro.core.ui.buttons.IconButton
import org.skepsun.kototoro.core.ui.page.PageSummaryAction
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection
import org.skepsun.kototoro.suggestions.domain.SuggestionLanguage

/** Shared recommendation heading, direction navigation and result context; the host owns the grid and services. */
@Composable
fun SuggestionsHeader(
    selected: SuggestionDirection?,
    language: SuggestionLanguage,
    languageOnly: Boolean,
    count: Int,
    onSelect: (SuggestionDirection?) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    refreshing: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.tertiary
    val languageText = if (language == SuggestionLanguage.ALL) suggestionLanguageLabel(language)
        else uiString(if (languageOnly) "suggestion_language_only_badge" else "suggestion_language_preferred_badge",
            suggestionLanguageLabel(language))
    // The direction pills sit in a 36dp touch box around a 32dp body; 6dp here reads as an 8dp gap.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, accent.copy(alpha = .10f)), modifier = Modifier.testTag("suggestions-summary")) {
            BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.horizontalGradient(
                listOf(accent.copy(alpha = .07f), colors.surfaceContainerLow),
            )).padding(14.dp)) {
                val compact = maxWidth < 480.dp
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(uiString("suggestions_header_title"), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(uiString("suggestion_results_count", count) + " · " + languageText,
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
                            if (compact) {
                                IconButton(onClick = onSettings, enabled = enabled,
                                    modifier = Modifier.testTag("suggestions-settings")) {
                                    Icon(ReaderPanelIcons.Settings, uiString("suggestion_preferences_action"), tint = accent)
                                }
                                FilledTonalIconButton(onClick = onRefresh, enabled = enabled && !refreshing,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = accent, contentColor = colors.onTertiary),
                                    modifier = Modifier.testTag("suggestions-refresh")) {
                                    Icon(ReaderPanelIcons.Sync, uiString("suggestions_header_refresh"))
                                }
                            } else {
                                PageSummaryAction(text = uiString("suggestion_preferences_action"), imageVector = ReaderPanelIcons.Settings,
                                    accent = accent, onClick = onSettings, enabled = enabled,
                                    modifier = Modifier.testTag("suggestions-settings"))
                                PageSummaryAction(text = uiString("suggestions_header_refresh"), imageVector = ReaderPanelIcons.Sync,
                                    accent = accent, onClick = onRefresh, enabled = enabled && !refreshing, emphasized = true,
                                    modifier = Modifier.testTag("suggestions-refresh"))
                            }
                        }
                    }
                    Text(when (selected) {
                        null -> uiString("suggestion_description_all")
                        SuggestionDirection.TASTE -> uiString("suggestion_description_taste")
                        SuggestionDirection.POPULAR -> uiString("suggestion_description_popular")
                        SuggestionDirection.NEW -> uiString("suggestion_description_new")
                        SuggestionDirection.EXPLORE -> uiString("suggestion_description_explore")
                    }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SuggestionDirectionChips(selected, onSelect, enabled = enabled)
    }
}

package org.skepsun.kototoro.core.ui.suggestions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.suggestions.domain.SuggestionLanguage

@Composable
fun suggestionLanguageLabel(language: SuggestionLanguage): String = when (language) {
    SuggestionLanguage.SYSTEM -> uiString("suggestion_language_system")
    SuggestionLanguage.CHINESE -> uiString("suggestion_language_chinese")
    SuggestionLanguage.ENGLISH -> uiString("suggestion_language_english")
    SuggestionLanguage.JAPANESE -> uiString("suggestion_language_japanese")
    SuggestionLanguage.ALL -> uiString("suggestion_language_all")
}

/** The same mixed/only-language semantics on Android and desktop. */
@Composable
fun SuggestionLanguageSelector(
    selected: SuggestionLanguage,
    only: Boolean,
    onSelect: (SuggestionLanguage) -> Unit,
    onOnlyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(uiString("suggestion_language_title"), style = MaterialTheme.typography.titleSmall)
        Text(uiString("suggestion_language_summary"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SuggestionLanguage.entries.forEachIndexed { index, language ->
                org.skepsun.kototoro.core.ui.chips.CompactChoicePill(selected == language, onClick = { onSelect(language) }, enabled = enabled,
                    label = suggestionLanguageLabel(language),
                    position = org.skepsun.kototoro.core.ui.chips.choicePillPosition(index, SuggestionLanguage.entries.size),
                    modifier = Modifier.testTag("suggestion-language:${language.name}"))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(uiString("suggestion_language_only"), modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium)
            Switch(only, onCheckedChange = onOnlyChanged, enabled = enabled && selected != SuggestionLanguage.ALL,
                modifier = Modifier.testTag("suggestion-language-only"))
        }
    }
}

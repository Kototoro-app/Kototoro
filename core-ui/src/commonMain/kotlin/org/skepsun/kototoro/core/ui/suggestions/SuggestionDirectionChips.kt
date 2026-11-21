package org.skepsun.kototoro.core.ui.suggestions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.core.ui.chips.CompactChoicePill
import org.skepsun.kototoro.core.ui.chips.ChoicePillPosition
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection

/** Null selects the mixed feed; changing direction only filters the stored results, without source requests. */
@Composable
fun SuggestionDirectionChips(
    selected: SuggestionDirection?,
    onSelect: (SuggestionDirection?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasCommunity: Boolean = false,
) {
    val directions = listOf(null) + SuggestionDirection.entries.filter { hasCommunity || it != SuggestionDirection.COMMUNITY }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        directions.forEachIndexed { index, direction ->
            val label = when (direction) {
                null -> uiString("suggestion_direction_all")
                SuggestionDirection.TASTE -> uiString("suggestion_direction_taste")
                SuggestionDirection.POPULAR -> uiString("suggestion_direction_popular")
                SuggestionDirection.NEW -> uiString("suggestion_direction_new")
                SuggestionDirection.EXPLORE -> uiString("suggestion_direction_explore")
                SuggestionDirection.COMMUNITY -> uiString("suggestion_direction_community")
            }
            CompactChoicePill(
                selected = selected == direction,
                onClick = { onSelect(direction) },
                label = label,
                position = when (index) {
                    0 -> ChoicePillPosition.FIRST
                    directions.lastIndex -> ChoicePillPosition.LAST
                    else -> ChoicePillPosition.MIDDLE
                },
                enabled = enabled,
                modifier = Modifier.testTag("suggestion-direction:${direction?.name ?: "ALL"}"),
            )
        }
    }
}

package org.skepsun.kototoro.core.ui.chapters

import org.skepsun.kototoro.core.ui.chips.ChoicePillPosition
import org.skepsun.kototoro.core.ui.chips.choicePillPosition
import org.skepsun.kototoro.core.ui.chips.CompactChoicePill
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll

/** Android's chapter list section header; [expandIcon] is shown (and rotated when collapsed) for collapsible groups. */
@Composable
fun ChapterSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    isCollapsible: Boolean = false,
    isExpanded: Boolean = true,
    expandIcon: Painter? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = isCollapsible, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (isCollapsible && expandIcon != null) {
            Icon(
                painter = expandIcon,
                contentDescription = if (!isExpanded) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (!isExpanded) -90f else 0f),
            )
        }
    }
}

/** The "branch · count" chip label Android shows above the chapter list. */
fun chapterBranchChipLabel(title: String, count: Int): String = if (count > 0) "$title · $count" else title

/** Android's horizontally scrolling branch chips; [title] names a branch (hosts localise the null branch). */
@Composable
fun ChapterBranchChips(
    options: List<ChapterBranchOption>,
    selected: String?,
    title: (String?) -> String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipModifier: @Composable (ChapterBranchOption) -> Modifier = { Modifier },
) {
    ChapterFilterChips(options, key = { it.name ?: "\u0000" },
        label = { chapterBranchChipLabel(title(it.name), it.chaptersCount) },
        selected = { it.name == selected }, onClick = { onSelect(it.name) }, modifier = modifier,
        enabled = enabled, connected = true, chipModifier = chipModifier)
}

/** Android's chapter filter rail, with platform models and TV focus adapted by its callers. */
@Composable
fun <T> ChapterFilterChips(
    options: List<T>,
    key: (T) -> Any,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    connected: Boolean = false,
    chipModifier: @Composable (T) -> Modifier = { Modifier },
) {
    if (options.isEmpty()) return
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().mouseDragScroll(state, Orientation.Horizontal),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (connected) 2.dp else 6.dp),
    ) {
        itemsIndexed(options, key = { _, option -> key(option) }) { index, option ->
            CompactChoicePill(
                selected = selected(option),
                onClick = { onClick(option) },
                enabled = enabled,
                modifier = chipModifier(option),
                label = label(option),
                position = if (connected) choicePillPosition(index, options.size)
                    else ChoicePillPosition.SINGLE,
            )
        }
    }
}

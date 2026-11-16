package org.skepsun.kototoro.core.ui.adaptive

import org.skepsun.kototoro.core.ui.chips.CompactChoicePill
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Compact choices on phones and desktop; TV keeps its large targets and visible remote-focus ring. */
@Composable
fun TvFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!LocalUiPresentationConfig.current.isTv) {
        CompactChoicePill(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val container = if (selected) colors.primaryContainer else colors.surfaceVariant
    val content = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(color = container, shape = shape)
            .tvFocusable(shape = shape, addFocusTarget = false)
            .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Text(text = label)
        }
    }
}

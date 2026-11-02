package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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

/**
 * A [FilterChip] a remote can actually see, for a chip row that is the only way to pick a value on TV.
 *
 * Material3's chip only paints a focus state layer, and from a sofa that reads as "nothing happened",
 * so the chip stops the remote without telling anyone where it went. The TV branch draws the same
 * pill by hand - thick enough to hit from three metres, with the shared focus ring - and the phone
 * branch still returns the stock chip, so screens that share this row keep their Material look.
 *
 * Use it where a chip row carries a real choice (a preset, a ratio, a mode). A chip that merely
 * labels something does not need it.
 */
@Composable
fun TvFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!LocalUiPresentationConfig.current.isTv) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
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

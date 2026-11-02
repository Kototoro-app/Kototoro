package org.skepsun.kototoro.main.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/** Keep FilterChip usage for real single/multi filter semantics only. */
@Composable
internal fun WizardFilterChip(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        // A TV remote needs to see where focus is: without the ring the chips are
        // an invisible minefield of D-pad stops. The chip owns the focus target
        // (its own clickable), so the ring only observes it.
        modifier = modifier.tvFocusable(
            enabled = enabled,
            shape = FilterChipDefaults.shape,
            addFocusTarget = false,
        ),
        label = label,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
    )
}

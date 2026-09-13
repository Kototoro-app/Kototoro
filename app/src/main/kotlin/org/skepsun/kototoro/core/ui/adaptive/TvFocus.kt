package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Adds a keyboard/remote focus target only for the TV presentation.
 *
 * Set addFocusTarget to false when the following clickable/combinedClickable modifier already
 * owns the focus target. The outline then observes that same target without adding a second one.
 */
@Composable
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    shape: Shape? = null,
    borderWidth: Dp = 2.dp,
    addFocusTarget: Boolean = true,
): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this

    var isFocused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .then(if (addFocusTarget) Modifier.focusable(enabled) else Modifier)
        .then(
            if (enabled && isFocused && shape != null) {
                Modifier.border(borderWidth, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            },
        )
}

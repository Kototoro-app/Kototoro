package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

private val DialogOptionRowShape = RoundedCornerShape(12.dp)

/**
 * One option inside an [androidx.compose.material3.AlertDialog] body.
 *
 * Off TV this is the stock full-width `TextButton` the dialog used before - a primary-coloured label
 * that reads as a link, which is what a phone user taps. A television has no such affordance: the
 * D-pad only shows a state layer on the label, so a two-option dialog looks like two captions and the
 * remote cannot tell which one a press would take. On TV the option becomes a full-width row with the
 * same 12dp focus ring the rest of the TV chrome uses, and the label is plain `bodyLarge` on surface
 * colour so it reads as a choice instead of a link.
 */
@Composable
internal fun DialogChoiceOption(
    label: String,
    onClick: () -> Unit,
) {
    if (LocalUiPresentationConfig.current.isTv) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusable(shape = DialogOptionRowShape, addFocusTarget = false)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        TvTextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = label, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A dialog's confirm/dismiss button. Material3 paints focus with a state layer only, which is
 * invisible next to a filled primary button on a television; on TV this adds the pill-shaped ring
 * the rest of the TV chrome uses and keeps the stock button (and its state layer) everywhere else.
 */
@Composable
internal fun DialogActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (LocalUiPresentationConfig.current.isTv) {
            Modifier.tvFocusable(enabled = enabled, shape = CircleShape, addFocusTarget = false)
        } else {
            Modifier
        },
    ) {
        Text(text = label)
    }
}

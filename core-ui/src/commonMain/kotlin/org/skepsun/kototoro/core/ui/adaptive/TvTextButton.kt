package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * A [TextButton] that shows an outline while a remote rests on it.
 *
 * A text button is a bare label on the dialog or panel it sits in: Material draws only the faint
 * state layer for a focused container, which from a sofa looks like nothing at all. Two answers side
 * by side - the confirm and the cancel of every destructive question in the app - were therefore
 * indistinguishable without touching the screen. The outline follows the button's own shape, so the
 * call sites keep whatever corner radius they asked for.
 *
 * The outline piggybacks on the focus target [TextButton] already owns (`addFocusTarget = false`),
 * so this adds no extra stop to the D-pad walk. No-op off TV: the same call sites stay byte for byte
 * the same button on phones and tablets.
 */
@Composable
fun TvTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.tvFocusable(
            enabled = enabled,
            shape = shape,
            addFocusTarget = false,
        ),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

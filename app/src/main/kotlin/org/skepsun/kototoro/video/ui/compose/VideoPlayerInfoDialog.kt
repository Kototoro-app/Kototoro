package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus

@Composable
internal fun VideoPlayerInfoDialog(
    details: String,
    onDismissRequest: () -> Unit,
) {
    val dialogShape = RoundedCornerShape(18.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // A remote cannot scroll the read-only body, so the only reachable target is the
                // close button; request it explicitly so the dialog opens with a visible ring.
                modifier = Modifier
                    .fillMaxWidth()
                    .tvTakeInitialFocus(),
            ) {
                Text(
                    text = stringResource(R.string.video_detail),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(40.dp)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                    )
                }
            }
        },
        text = {
            SelectionContainer {
                Text(
                    text = details,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier
                        .fillMaxWidth()
                        // A 480dp body overflows a 1080p television: the card ran off the bottom edge
                        // and the last statistics were clipped behind the screen border. The dialog
                        // keeps an empty button row, so the body has to stay well under half the screen.
                        .heightIn(max = if (isTvPlayerMenu()) 300.dp else 480.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {},
        shape = dialogShape,
        containerColor = playerMenuContainerColor(phoneAlpha = 0.86f),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
        modifier = Modifier.border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.16f),
            shape = dialogShape,
        ),
    )
}

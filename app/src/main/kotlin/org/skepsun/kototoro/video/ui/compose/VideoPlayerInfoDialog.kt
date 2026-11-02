package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus

@Composable
internal fun VideoPlayerInfoDialog(
    details: String,
    onDismissRequest: () -> Unit,
) {
    val dialogShape = RoundedCornerShape(18.dp)
    val isTv = isTvPlayerMenu()
    val scrollState = rememberScrollState()
    // The beam search from the close button does not reach the body: the two nodes overlap only in a
    // narrow vertical strip at the card's right edge, so a Down press used to leave the dialog with no
    // focus at all - every following key was swallowed. The two targets are therefore wired to each
    // other explicitly instead of relying on geometry.
    val closeRequester = remember { FocusRequester() }
    val bodyRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // The body is a scrollable read-only block, so the close button is the target that
                // must be ringed the moment the dialog opens; the body is one D-pad step further.
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
                        .focusRequester(closeRequester)
                        .focusProperties { down = bodyRequester }
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
                        .heightIn(max = if (isTv) 300.dp else 480.dp)
                        .then(
                            if (isTv) {
                                Modifier
                                    .focusRequester(bodyRequester)
                                    .focusProperties { up = closeRequester }
                                    .tvScrollableBody(scrollState)
                            } else {
                                Modifier
                            },
                        )
                        .verticalScroll(scrollState),
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

/**
 * Makes a read-only scrollable block drivable with a remote.
 *
 * A `verticalScroll` body is a dead focus stop on a television: the D-pad has nothing to move, so the
 * tail of a long details list is unreachable and the user only learns the dialog is scrollable by
 * accident. The block is therefore a focus target of its own, ringed, and up/down scroll it by most of
 * a viewport during the preview pass — at either end the key falls through so focus can leave again.
 */
@Composable
private fun Modifier.tvScrollableBody(scrollState: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return this
        .tvFocusable(shape = RoundedCornerShape(12.dp))
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                return@onPreviewKeyEvent false
            }
            // viewportSize is zero until the first layout pass; fall back to a fixed nudge so an
            // early press still moves the text instead of doing nothing.
            val step = when (val viewport = scrollState.viewportSize) {
                0 -> BODY_SCROLL_MIN_STEP
                else -> viewport * BODY_SCROLL_FRACTION
            }
            when {
                event.key == Key.DirectionUp && scrollState.value > 0 -> {
                    scope.launch { scrollState.animateScrollBy(-step) }
                    true
                }

                event.key == Key.DirectionUp -> false

                event.key == Key.DirectionDown -> {
                    // Consumed even at the last line: without a focus target below, an unhandled Down
                    // would drop the focus out of the dialog and the remote would look dead.
                    if (scrollState.value < scrollState.maxValue) {
                        scope.launch { scrollState.animateScrollBy(step) }
                    }
                    true
                }

                else -> false
            }
        }
}

private const val BODY_SCROLL_FRACTION = 0.8f
private const val BODY_SCROLL_MIN_STEP = 480f

package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Takes the focus for a surface that a remote has just opened.
 *
 * A dialog or a sheet lives in its own window: nothing inside it holds the focus, so without a
 * ring the user cannot tell where the D-pad would act. The group makes the first focusable child
 * the target. No-op off TV.
 */
@Composable
fun Modifier.tvTakeInitialFocus(): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        // The window needs a composition pass before a child is placed and focusable.
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return this.focusRequester(requester).focusGroup()
}

/**
 * Like [tvTakeInitialFocus], but for a pane whose content is swapped while the window keeps the
 * focus elsewhere (a master-detail split opening a page on the right).
 *
 * One attempt is not enough there: the new content is composed a few frames after the state change,
 * so a single `requestFocus` lands on nothing and the page opens without a ring. This keeps asking
 * while nothing inside holds the focus, and starts over whenever [key] changes, one key per page.
 * A healthy pane takes the focus on the first frame, which ends the loop before the user can react.
 * No-op off TV.
 */
@Composable
fun Modifier.tvTakeFocusWhenEmpty(key: Any?, attempts: Int = 20): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(key, requester) {
        hasFocus = false
        repeat(attempts) {
            withFrameNanos { }
            if (hasFocus) return@LaunchedEffect
            runCatching { requester.requestFocus() }
        }
    }
    return this
        .focusRequester(requester)
        .focusGroup()
        .onFocusChanged { hasFocus = it.hasFocus }
}

/**
 * Adds a keyboard/remote focus target only for the TV presentation.
 *
 * Order matters: place this modifier **before** the clickable/focusable node it should observe.
 * `onFocusChanged` only reports the focus target that follows it in the same modifier chain, and the
 * outline is drawn as an overlay above everything that comes after it - including a Material3
 * `Surface` background, which would otherwise paint over a plain `border`.
 *
 * Set addFocusTarget to false when the following clickable/combinedClickable modifier already
 * owns the focus target. The outline then observes that same target without adding a second one.
 *
 * ringColor is for controls whose container already is `colorScheme.primary` (a filled primary
 * button): the default primary ring would be invisible on them, so pass an on-container color.
 */
@Composable
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    shape: Shape? = null,
    borderWidth: Dp = 2.dp,
    addFocusTarget: Boolean = true,
    ringColor: Color = Color.Unspecified,
): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this

    var isFocused by remember { mutableStateOf(false) }
    val resolvedRingColor = if (ringColor == Color.Unspecified) {
        MaterialTheme.colorScheme.primary
    } else {
        ringColor
    }
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .then(if (addFocusTarget) Modifier.focusable(enabled) else Modifier)
        .then(
            if (enabled && shape != null) {
                Modifier.drawWithContent {
                    drawContent()
                    if (isFocused) {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        drawOutline(
                            outline = outline,
                            color = resolvedRingColor,
                            style = Stroke(width = borderWidth.toPx()),
                        )
                    }
                }
            } else {
                Modifier
            },
        )
}

/**
 * Outline ring for a tab inside a `TabRow`, a `ScrollableTabRow` or a `SecondaryTabRow`.
 *
 * Material3 tabs are `selectable`, so a remote already stops on them, but the only feedback is a
 * faint container tint that reads as nothing from a sofa, and a stop without an outline is the
 * same dead end as a control that cannot be focused at all. Pass this as the tab's own modifier:
 * a tab applies its modifier before the internal `selectable`, so the outline observes exactly
 * that stop without adding a second one. No-op off TV.
 */
@Composable
fun Modifier.tvTabRing(): Modifier = tvFocusable(
    shape = RoundedCornerShape(10.dp),
    addFocusTarget = false,
)

/**
 * Where one D-pad step from [value] lands: [direction] is -1 for left, +1 for right.
 *
 * A slider with [steps] discrete stops moves by one stop; a continuous slider moves by a twentieth
 * of its range, which is coarse enough to cross a 0..1 range in a few presses and fine enough to
 * stay useful. The result snaps back onto that grid, so repeated presses never accumulate float
 * drift, and it is clamped inside [valueRange].
 */
fun tvSliderStepValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    direction: Int,
): Float {
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return valueRange.start
    val step = span / if (steps > 0) steps + 1 else TV_SLIDER_STEPS
    val index = ((value.coerceIn(valueRange) - valueRange.start) / step).roundToInt() + direction
    return (valueRange.start + index * step).coerceIn(valueRange)
}

private const val TV_SLIDER_STEPS = 20

/**
 * Makes a Compose [Slider] drivable with a remote: a focus ring plus left/right stepping.
 *
 * A Slider keeps the D-pad for itself but only reacts to a drag, so on a TV it becomes a one-way
 * dead end — focus is inside it, nothing changes, and only up/down gets out again. The keys are
 * therefore handled during the preview pass, before the slider sees them, and committed through
 * [onValueChange]. Place this modifier **before** the slider it belongs to; on phones it is a no-op.
 */
@Composable
fun Modifier.tvSliderDpad(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this
    val step: (Int) -> Boolean = { direction ->
        val next = tvSliderStepValue(value, valueRange, steps, direction)
        if (next != value) {
            onValueChange(next)
        }
        true
    }
    return this
        .tvFocusable(enabled = enabled, shape = shape, addFocusTarget = false)
        .onPreviewKeyEvent { event ->
            when {
                !enabled || event.type != KeyEventType.KeyDown -> false
                event.key == Key.DirectionLeft -> step(-1)
                event.key == Key.DirectionRight -> step(1)
                else -> false
            }
        }
}

/**
 * Swallows the horizontal key at the edge of a row so focus cannot slide out of the container.
 *
 * Directional search happily leaves a panel sideways: from the left-most quick action the next LEFT
 * lands on anything focusable beside it, and an in-window sheet usually has the dismissable scrim
 * right there — focus lands on the scrim, one CENTER press closes the panel the user was working in.
 * Consuming the edge press keeps the focus where the user expects it.
 *
 * Apply it to the first/last item of a row (the modifier is a no-op off TV).
 */
@Composable
fun Modifier.tvHorizontalEdgeGuard(blockLeft: Boolean = false, blockRight: Boolean = false): Modifier {
    if (!blockLeft && !blockRight) return this
    if (!LocalUiPresentationConfig.current.isTv) return this
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            (blockLeft && event.key == Key.DirectionLeft) || (blockRight && event.key == Key.DirectionRight)
        }
    }
}

/**
 * Swallows the vertical key at the top or bottom edge of a stack so focus cannot slide out of it.
 *
 * The first row of the reader's quick layer sits under a header that collapses to the drag handle
 * once the sheet is expanded: its children stay composed and focusable, so UP from the top control
 * lands on a row nobody can see and the ring appears to vanish for a press. Consuming the press at
 * the edge keeps the focus on the control the user is looking at. Apply it to the top-most (or
 * bottom-most) row of the stack; the modifier is a no-op off TV.
 */
@Composable
fun Modifier.tvVerticalEdgeGuard(blockUp: Boolean = false, blockDown: Boolean = false): Modifier {
    if (!blockUp && !blockDown) return this
    if (!LocalUiPresentationConfig.current.isTv) return this
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            (blockUp && event.key == Key.DirectionUp) || (blockDown && event.key == Key.DirectionDown)
        }
    }
}

/**
 * Lets a remote leave a focused text field instead of trapping the D-pad inside it.
 *
 * Compose text fields keep the hardware arrow keys for caret movement, so on a TV the up/down
 * keys never reach the focus system and the field becomes a dead end: the user has to press back
 * (which may dismiss the whole screen) to reach the list below. Up/down therefore move focus out of
 * the field and hide the IME; left/right stay with the caret because editing a single-line value
 * still needs them.
 *
 * While the on-screen IME is open it owns the D-pad for its own key navigation, so the user first
 * closes the keyboard (Back or the IME search/done key) and then moves on with up/down. Without this
 * modifier that second step is impossible: the field keeps the arrow keys and focus never leaves.
 *
 * Pass [horizontalWhenIdle] for a field that sits between two toolbar buttons (a search bar): once
 * the keyboard is closed nobody drives the caret with the horizontal arrows any more, so they move
 * focus to the neighbouring button instead of doing nothing visible.
 *
 * Apply it to single-line fields only: a multi-line field needs up/down for caret movement.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.tvTextInputFocusEscape(horizontalWhenIdle: Boolean = false): Modifier {
    val isTv = LocalUiPresentationConfig.current.isTv
    if (!isTv) return this
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // `WindowInsets.isImeVisible` only exists in the Android artifact; the common build reads the same ime inset.
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> if (horizontalWhenIdle && !imeVisible) {
                    FocusDirection.Left
                } else {
                    null
                }

                Key.DirectionRight -> if (horizontalWhenIdle && !imeVisible) {
                    FocusDirection.Right
                } else {
                    null
                }

                else -> null
            }
            val moved = direction != null && focusManager.moveFocus(direction)
            if (moved) {
                keyboardController?.hide()
                true
            } else {
                false
            }
        }
    }
}

/**
 * Up/down as "finish typing" for a text field that lives alone in a dialog.
 *
 * Inside a dialog the focus search answers from the text slot, which holds nothing but the field, so
 * a directional move either finds no neighbour or reports a move that stays inside the field: after
 * the keyboard is closed the remote appears stuck even though OK sits two rows below.
 * [rememberTvFieldExit] already owns an explicit target, so the exit that Enter and the IME Done
 * action perform is reused here, and it is installed *before* [tvTextInputFocusEscape] so the
 * unreliable search never gets the first word. No-op off TV, and never installed on a multi-line
 * field, which needs up/down for its own line navigation.
 */
@Composable
private fun Modifier.tvTextInputVerticalExit(onExit: () -> Unit): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this
    return this.onPreviewKeyEvent { event ->
        val verticalExit = event.key == Key.DirectionUp || event.key == Key.DirectionDown
        if (event.type == KeyEventType.KeyDown && verticalExit) {
            onExit()
            true
        } else {
            false
        }
    }
}

/**
 * Centre as "start typing" for a field that is only holding a focus ring.
 *
 * A focused editable field pulls up the software keyboard on its own. On a TV that keyboard is wide and
 * sits at the bottom of the screen, so it covers whatever the dialog put there: the viewer sees a field
 * and no button to confirm it with. Call sites keep such a field read-only until the centre key asks for
 * editing, which keeps the whole dialog visible while it only carries the focus ring, and turns the
 * keyboard into something the viewer asked for rather than something that greeted them.
 */
fun Modifier.tvTextInputStartEditing(onStartEditing: () -> Unit): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
            onStartEditing()
            true
        } else {
            false
        }
    }

/**
 * Enter as "finish typing" for a TV text field.
 *
 * The route between fields belongs to the IME while it is open, and Back dismisses the whole host,
 * so the remote needs one key that ends editing. On the on-screen keyboard that key is the IME's
 * Done action; on a hardware remote or a hardware keyboard the same intent arrives as Enter, which
 * a text field would otherwise swallow. This makes Enter call [onEnter] on TV only, leaving phone
 * and tablet behaviour untouched.
 */
@Composable
fun Modifier.tvTextInputEnterAction(onEnter: () -> Unit): Modifier {
    if (!LocalUiPresentationConfig.current.isTv) return this
    return this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
            onEnter()
            true
        } else {
            false
        }
    }
}

/**
 * "Focus the field first, type on request" for a dialog field on TV.
 *
 * The whole point of a dialog is that its buttons stay visible: a field that raises the keyboard the
 * moment it takes the ring pushes `OK` off the bottom of a 1080p screen, so the viewer sees an input
 * and no way to confirm it. This holder keeps the field read-only until the centre key asks for
 * editing, and drops back to read-only as soon as the ring leaves, so opening the dialog a second
 * time starts from the same quiet state.
 *
 * Wire it up as
 * `readOnly = state.isReadOnly` and `modifier = Modifier.then(state.fieldModifier).then(fieldExit.fieldModifier)`.
 * Off TV [isReadOnly] is false and [fieldModifier] is empty, so phones and tablets are unaffected.
 */
@Stable
class TvTextFieldReadOnlyState internal constructor(
    /** Feed the field's `readOnly` parameter. False everywhere but TV. */
    val isReadOnly: Boolean,
    /** Attach to the text field: centre starts editing, losing the ring stops it. */
    val fieldModifier: Modifier,
)

/**
 * @param showKeyboard what the centre key does once the field becomes editable; defaults to showing
 * the software keyboard, which is what a read-only field needs to become a working one.
 */
@Composable
fun rememberTvTextFieldReadOnlyState(
    showKeyboard: (() -> Unit)? = null,
): TvTextFieldReadOnlyState {
    val isTv = LocalUiPresentationConfig.current.isTv
    val keyboardController = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    val fieldModifier = if (!isTv) {
        Modifier
    } else {
        Modifier
            .tvTextInputStartEditing {
                editing = true
                showKeyboard?.invoke() ?: keyboardController?.show()
            }
            .onFocusChanged { if (!it.hasFocus) editing = false }
    }
    return TvTextFieldReadOnlyState(
        isReadOnly = isTv && !editing,
        fieldModifier = fieldModifier,
    )
}

/**
 * Co-ordinated exit from one text field to the action that finishes the form.
 *
 * A dialog field traps a TV remote: the IME owns the D-pad while it is open, arrows only move the
 * caret once it is closed, and Back dismisses the whole dialog. Attach [fieldModifier] to the text
 * field and [targetModifier] to the button focus should move to, then call [onDone] from the field's
 * IME Done action. The button is addressed by [FocusRequester] on purpose: a geometric focus search
 * inside a scrollable dialog cannot find the next node, while an explicit request always can.
 *
 * When the target cannot take the focus - the usual cause is `enabled = false` on a confirm button
 * that waits for a non-blank value - the request is skipped in favour of the dialog's linear focus
 * order, so Done still leaves the field and lands on the next reachable control (normally Cancel).
 *
 * On phones and tablets both modifiers are no-ops, so the field behaves exactly as before.
 */
class TvFieldExit internal constructor(
    /** Hides the keyboard and moves focus onto the target button. */
    val onDone: () -> Unit,
    /** Attach to the text field. */
    val fieldModifier: Modifier,
    /** Attach to the button that finishes the form (Save/OK). */
    val targetModifier: Modifier,
)

/**
 * @param multiline pass true for a field that keeps its own newlines; the arrow-based escape is then
 * unnecessary, but the Done action still works.
 * @param target an existing [FocusRequester] to leave the field onto, when the form already owns one
 * for that control. [targetModifier] is then empty, so the caller keeps a single requester per node.
 * Use it for a form whose confirm button is disabled until the form is valid: the caller can point
 * the exit at whichever control is reachable right now instead of at a button that cannot take focus.
 */
@Composable
fun rememberTvFieldExit(
    multiline: Boolean = false,
    target: FocusRequester? = null,
): TvFieldExit {
    val ownedTarget = remember { FocusRequester() }
    val targetRequester = target ?: ownedTarget
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val leave: () -> Unit = {
        keyboardController?.hide()
        // A disabled button is not a focus target, so a form whose Save/OK waits for a
        // non-blank value answers requestFocus() with false. Without a fallback the
        // remote would be left standing on the field with no way out but Back, which
        // dismisses the whole dialog. Walk the dialog's own focus order instead: the
        // linear traversal skips the disabled button and lands on Cancel.
        if (!targetRequester.requestFocus()) {
            val moved = focusManager.moveFocus(FocusDirection.Next) ||
                focusManager.moveFocus(FocusDirection.Previous)
            if (!moved) {
                // Nothing else is reachable; drop the ring instead of pretending the
                // field was left, so the next D-pad press starts a fresh focus search.
                focusManager.clearFocus()
            }
        }
    }
    // Order matters: the explicit vertical exit goes first because the focus search inside a dialog
    // can answer "moved" while the ring stays on the field, which would swallow the key.
    val fieldModifier = Modifier
        .then(if (multiline) Modifier else Modifier.tvTextInputVerticalExit(leave))
        .tvTextInputEnterAction(leave)
        .then(if (multiline) Modifier else Modifier.tvTextInputFocusEscape())
    return TvFieldExit(
        onDone = leave,
        fieldModifier = fieldModifier,
        targetModifier = if (target == null) Modifier.focusRequester(ownedTarget) else Modifier,
    )
}

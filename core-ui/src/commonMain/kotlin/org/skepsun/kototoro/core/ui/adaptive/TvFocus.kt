package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Apply it to single-line fields only: a multi-line field needs up/down for caret movement.
 */
@Composable
fun Modifier.tvTextInputFocusEscape(): Modifier {
    val isTv = LocalUiPresentationConfig.current.isTv
    if (!isTv) return this
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
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
 * Co-ordinated exit from one text field to the action that finishes the form.
 *
 * A dialog field traps a TV remote: the IME owns the D-pad while it is open, arrows only move the
 * caret once it is closed, and Back dismisses the whole dialog. Attach [fieldModifier] to the text
 * field and [targetModifier] to the button focus should move to, then call [onDone] from the field's
 * IME Done action. The button is addressed by [FocusRequester] on purpose: a geometric focus search
 * inside a scrollable dialog cannot find the next node, while an explicit request always can.
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
 */
@Composable
fun rememberTvFieldExit(multiline: Boolean = false): TvFieldExit {
    val target = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val leave: () -> Unit = {
        keyboardController?.hide()
        target.requestFocus()
        Unit
    }
    val fieldModifier = Modifier
        .then(if (multiline) Modifier else Modifier.tvTextInputFocusEscape())
        .tvTextInputEnterAction(leave)
    return TvFieldExit(
        onDone = leave,
        fieldModifier = fieldModifier,
        targetModifier = Modifier.focusRequester(target),
    )
}

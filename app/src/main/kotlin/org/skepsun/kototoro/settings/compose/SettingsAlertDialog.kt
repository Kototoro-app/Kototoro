package org.skepsun.kototoro.settings.compose

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.glass.ApplyDynamicArtworkBlurDialogStyle
import org.skepsun.kototoro.core.ui.glass.findDialogWindow
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

@Composable
internal fun SettingsAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    bodyEditing: Boolean = false,
    text: @Composable () -> Unit,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val colors = MaterialTheme.colorScheme
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val bodyFocusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }
    var bodyHasFocus by remember { mutableStateOf(false) }
    var confirmHasFocus by remember { mutableStateOf(false) }
    var dismissHasFocus by remember { mutableStateOf(false) }
    val buttonsHaveFocus = confirmHasFocus || dismissHasFocus

    if (isTvPresentation) {
        // A dialog opens its own window, so nothing is focused unless someone asks. Without this the
        // remote either lands nowhere or keeps walking the dimmed screen behind the dialog. Prefer the
        // body - call sites put pickers there - and fall back to the buttons when the body has no
        // focusable child to offer. The fallback goes to the dismiss action: a confirmation is a yes/no
        // question and the key that opened the dialog must not be able to answer "yes" by accident
        // (delete/clear dialogs put the Confirm button on that spot). Dialogs without a dismiss action
        // still open on Confirm. The button attempts have to outlast the window's fade-in: a request
        // that lands while the dialog is still animating in - or while the app is in touch mode - is
        // dropped, which leaves the remote with no ring anywhere until the next key press.
        val buttonFocusRequester = if (dismissButton != null) dismissFocusRequester else confirmFocusRequester
        LaunchedEffect(buttonFocusRequester) {
            repeat(6) {
                withFrameNanos { }
                if (bodyHasFocus) return@LaunchedEffect
                runCatching { bodyFocusRequester.requestFocus() }
            }
            repeat(20) {
                withFrameNanos { }
                if (bodyHasFocus || buttonsHaveFocus) return@LaunchedEffect
                if (runCatching { buttonFocusRequester.requestFocus() }.getOrDefault(false)) {
                    return@LaunchedEffect
                }
            }
        }
    }

    ApplyDynamicArtworkBlurDialogStyle()
    if (isTvPresentation) {
        // A remote's only escape key is back, and on this dialog window the platform default never
        // reached the question: pressing it took the whole screen away and left the user somewhere
        // else entirely. Registered while the question is on screen, so it wins over the activity's
        // own back handling and only this dialog closes. No-op off TV.
        BackHandler { onDismissRequest() }
    }
    val dialogShape = RoundedCornerShape(tokens.dialogCornerRadius)
    val dialogTitle: @Composable () -> Unit = {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
    }
    val dialogBody: @Composable () -> Unit = {
        if (isTvPresentation) {
            TvDialogWindowSetup()
        }
        Box(
            modifier = if (isTvPresentation) {
                Modifier
                    .focusRequester(bodyFocusRequester)
                    .focusGroup()
                    .onFocusChanged { bodyHasFocus = it.hasFocus }
            } else {
                Modifier
            },
        ) {
            text()
        }
    }
    val dialogConfirm: @Composable () -> Unit = {
        Box(
            modifier = if (isTvPresentation) {
                Modifier
                    .focusRequester(confirmFocusRequester)
                    .focusGroup()
                    .onFocusChanged { confirmHasFocus = it.hasFocus }
            } else {
                Modifier
            },
        ) {
            confirmButton()
        }
    }
    val dialogDismiss: (@Composable () -> Unit)? = dismissButton?.let { content ->
        {
            Box(
                modifier = if (isTvPresentation) {
                    Modifier
                        .focusRequester(dismissFocusRequester)
                        .focusGroup()
                        .onFocusChanged { dismissHasFocus = it.hasFocus }
                } else {
                    Modifier
                },
            ) {
                content()
            }
        }
    }
    // Material3 hands this slot a whole row rather than the two buttons, and lays it out with a
    // wrapping flow row because a phone dialog is narrow. A TV dialog has room for the plain row,
    // which keeps Cancel / Clear / OK on one line and their spacing stable.
    val dialogButtons: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dialogDismiss?.invoke()
            dialogConfirm()
        }
    }
    if (isTvPresentation) {
        // A TV keyboard covers the lower half of the screen, and a dialog whose box stays centred in
        // the display loses its action row behind the keyboard: on this build that happened on five
        // tries out of six, and only a lucky re-layout pushed the box up. Nothing in the window can
        // be waited on here - the dialog window does not fold for the keyboard, and the keyboard
        // inset reads zero inside a dialog window. So move the box ourselves: the card sits at the
        // top of the screen once its field starts editing, clear of any keyboard height, and stays
        // there while the keyboard comes and goes instead of sliding up and down again.
        var dockedToTop by remember { mutableStateOf(false) }
        if (bodyEditing) {
            dockedToTop = true
        }
        BasicAlertDialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (dockedToTop) 32.dp else 0.dp),
                contentAlignment = if (dockedToTop) Alignment.TopCenter else Alignment.Center,
            ) {
                TvDialogCard(
                    title = dialogTitle,
                    icon = icon,
                    body = dialogBody,
                    buttons = dialogButtons,
                    shape = dialogShape,
                    containerColor = colors.surfaceContainerHighest,
                    borderColor = colors.outlineVariant,
                    tonalElevation = tokens.dialogTonalElevation,
                    titleColor = colors.onSurface,
                    bodyColor = colors.onSurfaceVariant,
                    buttonColor = colors.primary,
                )
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            shape = dialogShape,
            containerColor = colors.surfaceContainerHigh.copy(alpha = tokens.dialogContainerAlpha),
            tonalElevation = tokens.dialogTonalElevation,
            titleContentColor = colors.onSurface,
            textContentColor = colors.onSurfaceVariant,
            icon = icon,
            title = dialogTitle,
            text = dialogBody,
            confirmButton = dialogConfirm,
            dismissButton = dialogDismiss,
        )
    }
}

@Composable
internal fun SettingsDialogActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.tvFocusable(
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            addFocusTarget = false,
        ),
    ) {
        // A television is watched from across the room, so the action labels take the size the rest
        // of the TV settings list already uses instead of the phone's 14 sp.
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = if (LocalUiPresentationConfig.current.isTv) 16.sp else 14.sp,
            ),
        )
    }
}

/**
 * Width of a dialog card on TV: two thirds of a 1920 px screen, wide enough that a body sentence
 * fits on one line and narrow enough that the eye does not have to travel across the whole screen.
 */
private val TvDialogWidth = 640.dp

/** Hairline edge for the card: at ten feet a border is what tells the card from the scrim. */
private val TvDialogBorderWidth = 1.dp

/**
 * How much of the screen behind a TV dialog stays visible: 40% of it is covered.
 *
 * The platform default left the page at two thirds brightness, which on a dark theme put a card and
 * its backdrop within 19 levels of each other - the dialog read as a slightly lighter smudge in the
 * middle of the settings page.
 */
private const val TvDialogDimAmount = 0.6f

/**
 * The dialog box itself, on TV.
 *
 * Material3's own dialog content is shaped for a phone: a narrow card whose action row wraps in a
 * flow row. The same slots are laid out here instead, in the room a television has, so the action
 * row never wraps, and the card carries the glass tokens the rest of the TV chrome uses.
 *
 * The card takes a television width instead of hugging its title. A card that wraps its own text
 * moves its edges for every dialog, and a long body sentence then wraps into a narrow column beside
 * a wide empty card. `requiredWidth` is deliberate: Compose's dialog layout caps its content at
 * 560 dp (`DialogMaxWidth`), and this card is wider than that cap. The dialog window itself is
 * full-screen and nothing clips a child to that content box, so the card simply draws past it and
 * stays centred. Body text uses `bodyLarge` rather than the phone-sized `bodyMedium`, and the action
 * labels 16 sp: this card is only ever drawn at ten-foot distance, where 14 sp is below the size the
 * rest of the TV settings list already uses.
 *
 * The card is opaque and carries a hairline border. Both are about the edge at ten feet: with the
 * glass alpha the card measured 40 against a backdrop of 21, a difference no viewer reads as a card
 * boundary, and the border is what tells card from scrim once the two tones are close.
 */
@Composable
private fun TvDialogCard(
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)?,
    body: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
    shape: Shape,
    containerColor: Color,
    borderColor: Color,
    tonalElevation: Dp,
    titleColor: Color,
    bodyColor: Color,
    buttonColor: Color,
) {
    Surface(
        shape = shape,
        color = containerColor,
        tonalElevation = tonalElevation,
        border = BorderStroke(TvDialogBorderWidth, borderColor),
        modifier = Modifier.requiredWidth(TvDialogWidth),
    ) {
        Column(
            modifier = Modifier.padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 16.dp),
        ) {
            if (icon != null) {
                CompositionLocalProvider(LocalContentColor provides titleColor) {
                    icon()
                }
                Spacer(Modifier.height(16.dp))
            }
            CompositionLocalProvider(LocalContentColor provides titleColor) {
                title()
            }
            Spacer(Modifier.height(20.dp))
            CompositionLocalProvider(
                LocalContentColor provides bodyColor,
                LocalTextStyle provides MaterialTheme.typography.bodyLarge,
            ) {
                body()
            }
            Spacer(Modifier.height(24.dp))
            CompositionLocalProvider(LocalContentColor provides buttonColor) {
                buttons()
            }
        }
    }
}

/**
 * Set up the dialog window itself on TV: keep the keyboard out of its way and lay a proper scrim.
 *
 * The keyboard must not fold or pan this window: the card is moved to the top of the screen by
 * [SettingsAlertDialog] instead, and a window that also shifted itself would move the card twice.
 * The state bit keeps the request from raising a keyboard by itself - the field still decides when
 * editing starts.
 *
 * The scrim is heavier than the platform default because of viewing distance; see
 * [TvDialogDimAmount].
 *
 * Must be called from inside the dialog's own composition, which is where the dialog window can be
 * found: outside it the side effect lands on the activity's window and does nothing.
 */
@Composable
internal fun TvDialogWindowSetup() {
    val view = LocalView.current
    SideEffect {
        findDialogWindow(view)?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
            )
            setDimAmount(TvDialogDimAmount)
        }
    }
}

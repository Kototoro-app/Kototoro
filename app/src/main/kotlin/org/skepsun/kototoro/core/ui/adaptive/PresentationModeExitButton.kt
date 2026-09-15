package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings

/**
 * Explicit escape hatch for a manually selected TV presentation.
 * AUTO on a real TV remains automatic, so the button is only shown for TV
 * selected in settings.
 */
@Composable
fun PresentationModeExitButton(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    if (!LocalUiPresentationConfig.current.canRestoreStandard) return
    Button(
        onClick = { settings.uiPresentationMode = UiPresentationMode.STANDARD },
        modifier = modifier
            .padding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                    .asPaddingValues(),
            )
            .heightIn(min = 48.dp)
            .tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(stringResource(R.string.presentation_mode_restore_standard))
    }
}

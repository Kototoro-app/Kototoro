package org.skepsun.kototoro.migration.ui.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.buttons.IconButton

/** Floating, dismissible card telling the user some favourite sources look broken. */
@Composable
fun SourceHealthBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceHealthBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appSettings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val enabled by appSettings.observeAsState(AppSettings.KEY_SOURCE_HEALTH_BANNER) { isSourceHealthBannerEnabled }
    LaunchedEffect(enabled) { if (enabled) viewModel.refresh() }
    AnimatedVisibility(visible = enabled && state.sourceCount > 0, modifier = modifier) {
        SourceHealthBannerContent(
            message = pluralStringResource(
                R.plurals.migration_health_hint,
                state.sourceCount,
                state.sourceCount,
            ),
            onMigrate = onOpen,
            onDisable = viewModel::disable,
            onDismiss = viewModel::dismiss,
        )
    }
}

/**
 * Message first, actions underneath.
 *
 * The card used to be one `Row`: a `weight(1f)` message with "Migrate", "Don't show again" and the close button
 * beside it. At 1.5× those three actions alone are wider than a phone row, so the weighted message was given
 * whatever was left — about 30px — and "2 sources may be broken" was drawn as one letter per line, a card 2085px
 * tall that covered the library (seen on the emulator; `favourites-broken-banner-before-1.5.png`). Stacking the
 * message above the actions needs no measurement and holds at any font scale, unlike a narrower cap.
 */
@Composable
fun SourceHealthBannerContent(
    message: String,
    onMigrate: () -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvTextButton(onClick = onMigrate) { Text(stringResource(R.string.migration_title)) }
                TvTextButton(onClick = onDisable) { Text(stringResource(R.string.migration_health_banner_disable)) }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.tvIconButtonRing(),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
        }
    }
}

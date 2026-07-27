package org.skepsun.kototoro.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

data class UserTrackingAccountItem(
    val service: ScrobblerService,
    val title: String,
    val summary: String,
    val statsSummary: String?,
    val avatarUrl: String?,
    @DrawableRes val iconRes: Int,
)

data class UsersSettingsUiState(
    val accounts: List<UserTrackingAccountItem>,
    val preferredTrackingSite: ScrobblerService,
    val preferredTrackingSiteOptions: List<SettingsChoiceOption<ScrobblerService>>,
)

@Composable
fun UsersSettingsScreen(
    trackingAccountsTitle: String,
    state: UsersSettingsUiState,
    snackbarHostState: SnackbarHostState,
    pendingAuthService: ScrobblerService?,
    onDismissAuthPrompt: () -> Unit,
    onConfirmAuthPrompt: (ScrobblerService) -> Unit,
    onPreferredTrackingSiteChange: (ScrobblerService) -> Unit,
    onTrackingServiceClick: (ScrobblerService) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "browse_defaults") {
                SettingsPreferenceSection(title = stringResource(R.string.discover)) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.preferred_tracking_site),
                        value = state.preferredTrackingSite,
                        options = state.preferredTrackingSiteOptions,
                        summary = stringResource(R.string.preferred_tracking_site_summary),
                        onValueChange = onPreferredTrackingSiteChange,
                    )
                }
            }
            item(key = "tracking_accounts") {
                SettingsPreferenceSection(title = trackingAccountsTitle) {
                    state.accounts.forEachIndexed { index, item ->
                        UserTrackingAccountRow(
                            item = item,
                            onClick = { onTrackingServiceClick(item.service) },
                        )
                        if (index != state.accounts.lastIndex) {
                            SettingsSectionDivider()
                        }
                    }
                }
            }
        }
    }

    pendingAuthService?.let { service ->
        SettingsAlertDialog(
            onDismissRequest = onDismissAuthPrompt,
            title = stringResource(service.titleResId),
            text = {
                Text(
                    text = stringResource(
                        R.string.scrobbler_auth_intro,
                        stringResource(service.titleResId),
                    ),
                )
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.sign_in),
                    onClick = { onConfirmAuthPrompt(service) },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissAuthPrompt,
                )
            },
        )
    }
}

@Composable
private fun UserTrackingAccountRow(
    item: UserTrackingAccountItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserTrackingAccountAvatar(
            avatarUrl = item.avatarUrl,
            iconRes = item.iconRes,
        )
        Spacer(modifier = Modifier.size(16.dp))
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.statsSummary?.let { stats ->
                Text(
                    text = stats,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UserTrackingAccountAvatar(
    avatarUrl: String?,
    @DrawableRes iconRes: Int,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl.isNullOrBlank()) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        } else {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp),
                error = rememberSafePainter(iconRes),
                fallback = rememberSafePainter(iconRes),
            )
        }
    }
}

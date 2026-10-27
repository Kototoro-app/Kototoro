package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSnackbarHost
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsAction
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsContent
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsRowIcons
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsUiState

/**
 * Android observes the preferences and keeps the page's snackbar host; the rows themselves come from the shared
 * [ServicesSettingsContent] that desktop consumes too. Android-only rows travel as state/action slots: the
 * browse-tracking pair (Windows has no carousel to feed) and the Discord RPC door.
 */
@Composable
fun ServicesSettingsScreen(
    state: ServicesSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onSuggestionsClick: () -> Unit,
    onBrowseTrackingRecommendationsChange: (Boolean) -> Unit,
    onBrowseMoreTrackingRecommendationsChange: (Boolean) -> Unit,
    onRelatedContentChange: (Boolean) -> Unit,
    onStatsClick: () -> Unit,
    onStatsEnabledChange: (Boolean) -> Unit,
    onReadingTimeChange: (Boolean) -> Unit,
    onDiscordSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            KototoroSnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        ServicesSettingsContent(
            state = state,
            onSuggestionsClick = onSuggestionsClick,
            onRelatedContentChange = onRelatedContentChange,
            onStatsClick = onStatsClick,
            onStatsEnabledChange = onStatsEnabledChange,
            onReadingTimeChange = onReadingTimeChange,
            modifier = Modifier.fillMaxSize()
                .padding(innerPadding)
                .padding(top = settingsContentTopInset())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
            onBrowseTrackingChange = onBrowseTrackingRecommendationsChange,
            onBrowseMoreTrackingChange = onBrowseMoreTrackingRecommendationsChange,
            icons = ServicesSettingsRowIcons(
                suggestions = painterResource(R.drawable.ic_suggestion),
                browseTracking = painterResource(R.drawable.ic_suggestion),
                browseMoreTracking = painterResource(R.drawable.ic_updated),
                relatedContent = painterResource(R.drawable.ic_heart_outline),
                stats = painterResource(R.drawable.ic_bar_chart),
                readingTime = painterResource(R.drawable.ic_timer),
                discord = painterResource(R.drawable.ic_discord),
            ),
            discordSettings = ServicesSettingsAction(
                title = stringResource(R.string.discord_rpc),
                summary = stringResource(R.string.discord_rpc_summary),
                onClick = onDiscordSettingsClick,
            ),
        )
    }
}

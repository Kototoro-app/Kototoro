package org.skepsun.kototoro.settings.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.DiscordSettingsScreen

@Composable
fun DiscordSettingsRoute(
    settings: AppSettings,
    viewModel: DiscordSettingsViewModel,
    onTokenClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val context = LocalContext.current
    val tokenStatePair by viewModel.tokenState.collectAsStateWithLifecycle()
    val (state, token) = tokenStatePair

    val tokenSummary = when (state) {
        TokenState.EMPTY -> null
        TokenState.REQUIRED -> null
        TokenState.INVALID -> null
        TokenState.VALID -> token?.let { context.getString(R.string.logged_in_as, it) }
        TokenState.CHECKING -> context.getString(R.string.loading_)
    }

    DiscordSettingsScreen(
        settings = settings,
        tokenSummary = tokenSummary,
        isLogoutVisible = settings.isDiscordRpcEnabled && state == TokenState.VALID,
        onTokenClick = onTokenClick,
        onLogoutClick = onLogoutClick,
    )
}

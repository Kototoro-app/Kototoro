package org.skepsun.kototoro.settings.discord

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.scrobbling.discord.ui.DiscordAuthActivity
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.compose.DiscordSettingsScreen
import javax.inject.Inject

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
        TokenState.INVALID -> token?.let { context.getString(R.string.invalid_token, it) }
        TokenState.VALID -> token
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

package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserProfile
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUserStats
import org.skepsun.kototoro.scrobbling.common.ui.ScrobblerAuthHelper
import org.skepsun.kototoro.settings.compose.UserTrackingAccountItem
import org.skepsun.kototoro.settings.compose.UsersSettingsScreen
import org.skepsun.kototoro.settings.compose.UsersSettingsUiState
import org.skepsun.kototoro.settings.users.TrackingUserAccountSummaryProvider
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService

@Composable
fun UsersSettingsRoute(
    settings: AppSettings,
    scrobblerAuthHelper: ScrobblerAuthHelper,
    trackingUserAccountSummaryProvider: TrackingUserAccountSummaryProvider,
    trackingDiscoveryService: TrackingSiteDiscoveryService,
    refreshKey: Int,
    onOpenScrobblerSettings: (ScrobblerService) -> Unit,
) {
    val context = LocalContext.current
    val preferredTrackingSite = settings.observeAsState(AppSettings.KEY_PREFERRED_TRACKING_SITE) {
        preferredTrackingSite
    }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var pendingAuthService by remember { mutableStateOf<ScrobblerService?>(null) }
    val preferredTrackingSiteOptions = remember(trackingDiscoveryService) {
        buildPreferredTrackingSiteOptions(context, trackingDiscoveryService)
    }
    val accounts = produceState(
        initialValue = buildPlaceholderItems(context, scrobblerAuthHelper),
        key1 = refreshKey,
        key2 = context,
    ) {
        value = loadAccountItems(
            context = context,
            scrobblerAuthHelper = scrobblerAuthHelper,
            trackingUserAccountSummaryProvider = trackingUserAccountSummaryProvider,
        )
    }.value

    UsersSettingsScreen(
        trackingAccountsTitle = context.getString(R.string.tracking_accounts),
        state = UsersSettingsUiState(
            accounts = accounts,
            preferredTrackingSite = preferredTrackingSite,
            preferredTrackingSiteOptions = preferredTrackingSiteOptions,
        ),
        snackbarHostState = snackbarHostState,
        pendingAuthService = pendingAuthService,
        onDismissAuthPrompt = { pendingAuthService = null },
        onConfirmAuthPrompt = { service ->
            pendingAuthService = null
            scrobblerAuthHelper.startAuth(context, service).onFailure {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(it.getDisplayMessage(context.resources))
                }
            }
        },
        onPreferredTrackingSiteChange = { service ->
            settings.preferredTrackingSite = service
        },
        onTrackingServiceClick = { service ->
            if (scrobblerAuthHelper.isAuthorized(service)) {
                onOpenScrobblerSettings(service)
            } else {
                pendingAuthService = service
            }
        },
    )
}

private fun buildPlaceholderItems(
    context: android.content.Context,
    scrobblerAuthHelper: ScrobblerAuthHelper,
): List<UserTrackingAccountItem> {
    return TRACKING_SERVICES.map { service ->
        val cachedUser = scrobblerAuthHelper.getCachedUser(service)
        UserTrackingAccountItem(
            service = service,
            title = context.getString(service.titleResId),
            summary = when {
                !scrobblerAuthHelper.isAuthorized(service) -> context.getString(R.string.not_signed_in)
                cachedUser?.nickname.isNullOrBlank() -> context.getString(R.string.loading_)
                else -> cachedUser?.nickname.orEmpty()
            },
            statsSummary = if (scrobblerAuthHelper.isAuthorized(service)) {
                null
            } else {
                null
            },
            avatarUrl = cachedUser?.avatar,
            iconRes = service.iconResId,
        )
    }
}

private suspend fun loadAccountItems(
    context: android.content.Context,
    scrobblerAuthHelper: ScrobblerAuthHelper,
    trackingUserAccountSummaryProvider: TrackingUserAccountSummaryProvider,
): List<UserTrackingAccountItem> = withContext(Dispatchers.IO) {
    TRACKING_SERVICES.map { service ->
        if (!scrobblerAuthHelper.isAuthorized(service)) {
            return@map UserTrackingAccountItem(
                service = service,
                title = context.getString(service.titleResId),
                summary = context.getString(R.string.not_signed_in),
                statsSummary = null,
                avatarUrl = null,
                iconRes = service.iconResId,
            )
        }
        runCatching {
            val profile = trackingUserAccountSummaryProvider.load(service)
            buildAccountItem(context, service, profile)
        }.getOrElse {
            it.printStackTraceDebug()
            val cachedUser = scrobblerAuthHelper.getCachedUser(service)
            UserTrackingAccountItem(
                service = service,
                title = context.getString(service.titleResId),
                summary = cachedUser?.nickname ?: it.getDisplayMessage(context.resources),
                statsSummary = null,
                avatarUrl = cachedUser?.avatar,
                iconRes = service.iconResId,
            )
        }
    }
}

private fun buildAccountItem(
    context: android.content.Context,
    service: ScrobblerService,
    profile: ScrobblerUserProfile,
): UserTrackingAccountItem {
    return UserTrackingAccountItem(
        service = service,
        title = context.getString(service.titleResId),
        summary = profile.user.nickname,
        statsSummary = formatStatsSummary(context, service, profile.stats),
        avatarUrl = profile.user.avatar,
        iconRes = service.iconResId,
    )
}

private fun formatStatsSummary(
    context: android.content.Context,
    service: ScrobblerService,
    stats: ScrobblerUserStats?,
): String {
    if (stats == null) {
        return context.getString(R.string.statistics_pending_short)
    }
    if (service == ScrobblerService.MAL) {
        return formatDualMediaStatsSummary(context, stats)
    }
    val parts = when (service) {
        ScrobblerService.MAL -> emptyList()
        ScrobblerService.ANILIST,
        ScrobblerService.KITSU,
        ScrobblerService.BANGUMI,
        ScrobblerService.MANGAUPDATES,
        ScrobblerService.SHIKIMORI,
        -> listOfNotNull(
            stats.animeCount?.let { context.getString(R.string.anime_count_short, it) },
            stats.mangaCount?.let { context.getString(R.string.manga_count_short, it) },
            stats.episodesWatched?.let { context.getString(R.string.episodes_watched_short, it) },
            stats.chaptersRead?.let { context.getString(R.string.chapters_read_short, it) },
            stats.animeMeanScore?.let { context.getString(R.string.mean_score_short, formatScore(it)) },
            stats.mangaMeanScore?.let { context.getString(R.string.mean_score_short, formatScore(it)) },
        )
        ScrobblerService.SIMKL -> listOfNotNull(
            stats.animeCount?.let { context.getString(R.string.anime_count_short, it) },
            stats.tvCount?.let { context.getString(R.string.tv_count_short, it) },
            stats.movieCount?.let { context.getString(R.string.movie_count_short, it) },
            stats.episodesWatched?.let { context.getString(R.string.episodes_watched_short, it) },
            stats.tvEpisodesWatched?.let { context.getString(R.string.tv_episodes_watched_short, it) },
        )
    }
    return parts.joinToString(separator = " · ").ifBlank {
        context.getString(R.string.statistics_pending_short)
    }
}

private fun formatDualMediaStatsSummary(
    context: android.content.Context,
    stats: ScrobblerUserStats,
): String {
    val animeLine = listOfNotNull(
        stats.animeCount?.let { context.getString(R.string.anime_count_short, it) },
        stats.episodesWatched?.let { context.getString(R.string.episodes_watched_short, it) },
        stats.animeMeanScore?.let { context.getString(R.string.mean_score_short, formatScore(it)) },
    ).joinToString(separator = " · ")

    val mangaLine = listOfNotNull(
        stats.mangaCount?.let { context.getString(R.string.manga_count_short, it) },
        stats.chaptersRead?.let { context.getString(R.string.chapters_read_short, it) },
        stats.mangaMeanScore?.let { context.getString(R.string.mean_score_short, formatScore(it)) },
    ).joinToString(separator = " · ")

    return listOf(animeLine, mangaLine)
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n")
        .ifBlank { context.getString(R.string.statistics_pending_short) }
}

private fun formatScore(score: Double): String {
    return if (score % 1.0 == 0.0) {
        score.toInt().toString()
    } else {
        String.format("%.1f", score)
    }
}

private fun buildPreferredTrackingSiteOptions(
    context: android.content.Context,
    trackingDiscoveryService: TrackingSiteDiscoveryService,
): List<SettingsChoiceOption<ScrobblerService>> {
    return TRACKING_SERVICES
        .filter { service -> trackingDiscoveryService.getCapabilities(service).supportsTrending }
        .map { service ->
            SettingsChoiceOption(
                value = service,
                label = context.getString(service.titleResId),
            )
        }
}

private val TRACKING_SERVICES = listOf(
    ScrobblerService.ANILIST,
    ScrobblerService.KITSU,
    ScrobblerService.MAL,
    ScrobblerService.SHIKIMORI,
    ScrobblerService.BANGUMI,
    ScrobblerService.MANGAUPDATES,
    ScrobblerService.SIMKL,
)

package org.skepsun.kototoro.settings

import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.compose.NotificationSettingsScreen
import org.skepsun.kototoro.settings.compose.NotificationSettingsUiState
import org.skepsun.kototoro.settings.utils.RingtonePickContract
import org.skepsun.kototoro.tracker.work.TrackerNotificationHelper
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NotificationSettingsLegacyFragment : Fragment() {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var notificationHelper: TrackerNotificationHelper

    private val ringtonePickContract = registerForActivityResult(
        RingtonePickContract(R.string.notification_sound),
    ) { uri ->
        settings.notificationSound = uri ?: return@registerForActivityResult
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as ComposeView).setContent {
            KototoroTheme {
                NotificationSettingsRoute(
                    settings = settings,
                    onNotificationSoundClick = {
                        ringtonePickContract.launch(settings.notificationSound)
                    },
                    onNotificationVibrateClick = {
                        notificationHelper.updateChannels()
                        startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, TrackerNotificationHelper.CHANNEL_ID),
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.notifications))
    }
}

@Composable
fun NotificationSettingsRoute(
    settings: AppSettings,
    onNotificationSoundClick: () -> Unit,
    onNotificationVibrateClick: () -> Unit,
) {
    val context = LocalContext.current
    val isTrackerNotificationsEnabled = settings.observeAsState(
        AppSettings.KEY_TRACKER_NOTIFICATIONS,
    ) { isTrackerNotificationsEnabled }.value
    val notificationSound = settings.observeAsState(
        AppSettings.KEY_NOTIFICATIONS_SOUND,
    ) { notificationSound }.value
    val notificationLight = settings.observeAsState(
        AppSettings.KEY_NOTIFICATIONS_LIGHT,
    ) { notificationLight }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val ringtoneSummary = RingtoneManager.getRingtone(context, notificationSound)
        ?.getTitle(context)
        ?: context.getString(R.string.silent)

    val state = NotificationSettingsUiState(
        isTrackerNotificationsEnabled = isTrackerNotificationsEnabled,
        ringtoneSummary = ringtoneSummary,
        isNotificationLightEnabled = notificationLight,
        isNotificationsInfoVisible = !isTrackerNotificationsEnabled,
    )

    NotificationSettingsScreen(
        notificationsTitle = context.getString(R.string.notifications),
        state = state,
        snackbarHostState = snackbarHostState,
        onTrackerNotificationsEnabledChange = { settings.isTrackerNotificationsEnabled = it },
        onNotificationSoundClick = onNotificationSoundClick,
        onNotificationVibrateClick = onNotificationVibrateClick,
        onNotificationLightChange = { settings.notificationLight = it },
    )
}

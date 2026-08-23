package org.skepsun.kototoro.extensions.recovery

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.R

/** Channel id for source-recovery notifications (`recovery_` prefix per T5.3). */
const val CHANNEL_ID_RECOVERY = "recovery_status"

/** Notification ids for the two recovery notification kinds. */
const val NOTIFICATION_ID_RECOVERED = 5101
const val NOTIFICATION_ID_MISSING_SUMMARY = 5102

/**
 * Simple source-recovery notifications (T5.3).
 *
 * Kept behind its own tiny seam so the coordinator stays unit-testable with a mock notifier
 * and so a missing notification permission / service can never break a recovery action:
 * every public method is wrapped in [runCatching] and degrades to a silent no-op.
 */
@Singleton
class RecoveryNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Small auto-cancelling notification: the source is back. */
    fun notifyRecovered(sourceKey: String) = runCatching {
        ensureChannel()
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID_RECOVERED,
            NotificationCompat.Builder(context, CHANNEL_ID_RECOVERY)
                .setSmallIcon(R.drawable.ic_empty_favourites)
                .setContentTitle("Source recovered")
                .setContentText(sourceKey)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    /** Summary notification when [missingCount] sources still need recovery. */
    fun notifyMissingSummary(missingCount: Int) = runCatching {
        if (missingCount <= 0) {
            return@runCatching
        }
        ensureChannel()
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID_MISSING_SUMMARY,
            NotificationCompat.Builder(context, CHANNEL_ID_RECOVERY)
                .setSmallIcon(R.drawable.ic_empty_favourites)
                .setContentTitle("Sources missing")
                .setContentText("$missingCount source(s) still need recovery")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        val manager = NotificationManagerCompat.from(context)
        if (manager.getNotificationChannel(CHANNEL_ID_RECOVERY) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID_RECOVERY, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Source recovery")
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .setSound(null, null)
                .build(),
        )
    }
}

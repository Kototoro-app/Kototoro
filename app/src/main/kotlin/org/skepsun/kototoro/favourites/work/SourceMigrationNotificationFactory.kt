package org.skepsun.kototoro.favourites.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.work.WorkManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.main.ui.MainActivity
import java.util.UUID
import androidx.appcompat.R as appcompatR

const val CHANNEL_ID_SOURCE_MIGRATION = "source_migration"
const val EXTRA_SHOW_MIGRATION_PANEL = "show_migration_panel"

class SourceMigrationNotificationFactory @AssistedInject constructor(
    @LocalizedAppContext private val context: Context,
    private val workManager: WorkManager,
    @Assisted private val uuid: UUID,
) {
    private val builder = NotificationCompat.Builder(context, CHANNEL_ID_SOURCE_MIGRATION)

    private val contentIntent by lazy {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_SHOW_MIGRATION_PANEL, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        PendingIntentCompat.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT, false,
        )
    }

    private val actionCancel by lazy {
        NotificationCompat.Action(
            appcompatR.drawable.abc_ic_clear_material,
            context.getString(android.R.string.cancel),
            workManager.createCancelPendingIntent(uuid),
        )
    }

    init {
        createChannel()
        builder
            .setSmallIcon(R.drawable.ic_empty_favourites)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
    }

    fun createProgress(
        total: Int,
        completed: Int,
        failed: Int,
        notFound: Int,
        currentTitle: String?,
    ): Notification {
        builder
            .setContentTitle(context.getString(R.string.source_migration_notification_title))
            .setProgress(total, completed, false)
            .clearActions()
            .addAction(actionCancel)

        val contentText = if (currentTitle != null) {
            context.getString(R.string.migration_status_active, currentTitle)
        } else {
            context.getString(R.string.migration_progress, completed, total)
        }
        builder.setContentText(contentText)

        builder.setStyle(
            NotificationCompat.BigTextStyle().bigText(
                context.getString(
                    R.string.migration_progress_detail,
                    completed, total, failed, notFound,
                ),
            ),
        )

        return builder.build()
    }

    fun createFinished(
        total: Int,
        completed: Int,
        failed: Int,
        notFound: Int,
        reused: Int,
        attached: Int,
    ): Notification {
        builder
            .setContentTitle(context.getString(R.string.migration_completed_title))
            .setContentText(context.getString(R.string.migration_completed_summary, completed, reused, attached, failed, notFound))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .clearActions()

        return builder.build()
    }

    private fun createChannel() {
        val manager = NotificationManagerCompat.from(context)
        if (manager.getNotificationChannel(CHANNEL_ID_SOURCE_MIGRATION) != null) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID_SOURCE_MIGRATION, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.source_migration_channel_name))
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .setSound(null, null)
                .build(),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(uuid: UUID): SourceMigrationNotificationFactory
    }
}

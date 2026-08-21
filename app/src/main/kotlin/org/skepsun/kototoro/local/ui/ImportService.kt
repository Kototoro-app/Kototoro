package org.skepsun.kototoro.local.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ErrorReporterReceiver
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.CoroutineIntentService
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toBitmapOrNull
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.withPartialWakeLock
import org.skepsun.kototoro.local.data.importer.ImportMode
import org.skepsun.kototoro.local.data.importer.LocalImportKind
import org.skepsun.kototoro.local.data.importer.SingleContentImporter
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class ImportService : CoroutineIntentService() {

    @Inject
    lateinit var importer: SingleContentImporter

    @Inject
    lateinit var coil: ImageLoader

    private lateinit var notificationManager: NotificationManagerCompat

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(applicationContext)
    }

    override suspend fun IntentJobContext.processIntent(intent: Intent) {
        val uri = requireNotNull(intent.getStringExtra(DATA_URI)?.toUriOrNull()) { "No input uri" }
        val importModeOrdinal = intent.getIntExtra(DATA_IMPORT_MODE, -1)
        val importMode = if (importModeOrdinal >= 0) ImportMode.entries.getOrNull(importModeOrdinal) else null
        val importKindOrdinal = intent.getIntExtra(DATA_IMPORT_KIND, -1)
        val importKind = if (importKindOrdinal >= 0) LocalImportKind.entries.getOrNull(importKindOrdinal) else null
        
        startForeground(this)
        powerManager.withPartialWakeLock(TAG) {
            val result = runCatchingCancellable {
                if (importMode != null) {
                    importer.import(uri, importMode, importKind).map { it.manga }
                } else {
                    importer.import(uri, importKind).map { it.manga }
                }
            }
            if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
                result.onSuccess { mangas ->
                    mangas.forEachIndexed { index, manga ->
                        val notification = buildSuccessNotification(startId, manga, mangas.size)
                        notificationManager.notify(TAG, startId * 100 + index, notification)
                    }
                }.onFailure { error ->
                    val notification = buildFailureNotification(startId, error)
                    notificationManager.notify(TAG, startId, notification)
                }
            }
        }
    }

    override fun IntentJobContext.onError(error: Throwable) {
        if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
            val notification = buildFailureNotification(startId, error)
            notificationManager.notify(TAG, startId, notification)
        }
    }

    @SuppressLint("InlinedApi")
    private fun startForeground(jobContext: IntentJobContext) {
        val title = applicationContext.getString(R.string.importing_manga)
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(title)
            .setShowBadge(false)
            .setVibrationEnabled(false)
            .setSound(null, null)
            .setLightsEnabled(false)
            .build()
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setDefaults(0)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        jobContext.setForeground(
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun buildSuccessNotification(startId: Int, manga: Content, totalCount: Int): Notification {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(0)
            .setSilent(true)
            .setAutoCancel(true)
        notification.setLargeIcon(
            coil.execute(
                ImageRequest.Builder(applicationContext)
                    .data(manga.coverUrl)
                    .mangaSourceExtra(manga.source)
                    .build(),
            ).toBitmapOrNull(),
        )
        notification.setSubText(manga.title)
        val intent = AppRouter.detailsIntent(applicationContext, manga)
        notification.setContentIntent(
            PendingIntentCompat.getActivity(
                applicationContext,
                (startId * 100 + manga.id.toInt()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            ),
        ).setVisibility(
            if (manga.isNsfw()) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC,
        )
        val title = if (totalCount > 1) {
            applicationContext.getString(R.string.import_completed_with_count, totalCount)
        } else {
            applicationContext.getString(R.string.import_completed)
        }
        notification.setContentTitle(title)
            .setContentText(applicationContext.getString(R.string.import_completed_hint))
            .setSmallIcon(R.drawable.ic_stat_done)
        NotificationCompat.BigTextStyle(notification)
            .bigText(applicationContext.getString(R.string.import_completed_hint))
        return notification.build()
    }

    private fun buildFailureNotification(startId: Int, error: Throwable): Notification {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(0)
            .setSilent(true)
            .setAutoCancel(true)
        notification.setContentTitle(applicationContext.getString(R.string.error_occurred))
            .setContentText(error.getDisplayMessage(applicationContext.resources))
            .setSmallIcon(android.R.drawable.stat_notify_error)
        ErrorReporterReceiver.getNotificationAction(
            context = applicationContext,
            e = error,
            notificationId = startId,
            notificationTag = TAG,
        )?.let { action ->
            notification.addAction(action)
        }
        return notification.build()
    }

    companion object {

        private const val DATA_URI = "uri"
        private const val DATA_IMPORT_MODE = "import_mode"
        private const val DATA_IMPORT_KIND = "import_kind"
        private const val TAG = "import"
        private const val CHANNEL_ID = "importing"
        private const val FOREGROUND_NOTIFICATION_ID = 37

        /**
         * Start import for files (CBZ/ZIP archives)
         */
        fun start(context: Context, uris: Collection<Uri>, kind: LocalImportKind? = null): Boolean = try {
            require(uris.isNotEmpty())
            for (uri in uris) {
                val intent = Intent(context, ImportService::class.java)
                intent.putExtra(DATA_URI, uri.toString())
                intent.putExtra(DATA_IMPORT_KIND, kind?.ordinal ?: -1)
                ContextCompat.startForegroundService(context, intent)
            }
            true
        } catch (e: Exception) {
            e.printStackTraceDebug()
            false
        }

        /**
         * Start import for directory with specified mode
         */
        fun start(context: Context, uri: Uri, mode: ImportMode, kind: LocalImportKind? = null): Boolean = try {
            val intent = Intent(context, ImportService::class.java)
            intent.putExtra(DATA_URI, uri.toString())
            intent.putExtra(DATA_IMPORT_MODE, mode.ordinal)
            intent.putExtra(DATA_IMPORT_KIND, kind?.ordinal ?: -1)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            e.printStackTraceDebug()
            false
        }
    }
}

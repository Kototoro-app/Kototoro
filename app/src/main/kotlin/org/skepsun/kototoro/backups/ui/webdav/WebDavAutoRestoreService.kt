package org.skepsun.kototoro.backups.ui.webdav

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupFlowPolicy
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.logBackupFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class WebDavAutoRestoreService : Service() {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var backupFlowPolicy: BackupFlowPolicy

    @Inject
    lateinit var runner: WebDavAutoRestoreRunner

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        BaseBackupRestoreService.createNotificationChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, BaseBackupRestoreService.CHANNEL_ID)
            .setContentTitle(getString(R.string.webdav_auto_restore))
            .setContentText(getString(R.string.checking_for_backups))
            .setSmallIcon(R.drawable.ic_backup_restore)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val decision = backupFlowPolicy.autoRestoreStartupDecision()
        if (!decision.allowed) {
            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "start_skipped", reason = decision.reason)
            stopSelf()
            return START_NOT_STICKY
        }

        val lastCheck = settings.backupWebDavLastAutoRestoreCheckTime
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        if (lastCheck > 0 && df.format(Date(lastCheck)) == df.format(Date())) {
            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "start_skipped", reason = "already_checked_today")
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                runner.run()
            } catch (e: Exception) {
                Log.e(TAG, "Auto restore failed", e)
                e.printStackTraceDebug()
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "WebDavAutoRestore"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, WebDavAutoRestoreService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

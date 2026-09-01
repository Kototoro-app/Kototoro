package org.skepsun.kototoro.backups.ui.restore

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.external.ExternalBackupDecoder
import org.skepsun.kototoro.backups.external.ExternalBackupApp
import org.skepsun.kototoro.backups.external.ExternalBackupImportSummary
import org.skepsun.kototoro.backups.external.ExternalBackupRepository
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.CompositeResult
import org.skepsun.kototoro.core.util.ext.getSerializableExtraCompat
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.withPartialWakeLock
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import java.io.FileNotFoundException
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class ExternalBackupImportService : BaseBackupRestoreService() {

    override val notificationTag = TAG
    override val isRestoreService = true

    @Inject
    lateinit var decoder: ExternalBackupDecoder

    @Inject
    lateinit var repository: ExternalBackupRepository

    @Inject
    lateinit var entityGraphRepository: EntityGraphRepository

    @Inject
    lateinit var settings: AppSettings

    override suspend fun IntentJobContext.processIntent(intent: Intent) {
        val notification = buildNotification()
        setForeground(
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val source = intent.getStringExtra(AppRouter.KEY_DATA)?.toUriOrNull() ?: throw FileNotFoundException()
        val app = intent.getStringExtra(EXTRA_APP)
            ?.let(ExternalBackupApp::valueOf)
            ?: throw IllegalArgumentException("Missing external backup app")
        powerManager.withPartialWakeLock(TAG) {
            val result = runCatching {
                val payload = withContext(Dispatchers.IO) { decoder.decode(source, app) }
                val summary = repository.import(payload)
                // Phase 2: consolidate provisional import entities (merge duplicate works
                // across sources) before reporting completion, so the reported state is final.
                val consolidation = runCatching { entityGraphRepository.consolidateImportProvisionalEntities() }
                val consolidationPending = consolidation.isFailure
                consolidation.onFailure { e ->
                        // 不能只 printStackTraceDebug：合并没跑完，库里就长期留着同一部作品的多个
                        // WORK 实体，用户在收藏/分类里看到的是重复项（issue #510）。
                        // 记成待办交给下次启动维护重试，并让导入结果明确不报「全部成功」。
                        Log.e(TAG, "Provisional entity consolidation failed; duplicate works may remain", e)
                        settings.isEntityConsolidationPending = true
                    }
                consolidation.onSuccess { settings.isEntityConsolidationPending = false }
                summary.copy(consolidationPending = consolidationPending)
            }
            result.fold(
                onSuccess = { summary ->
                    // Prefer a foreground summary dialog; fall back to toast + notification
                    // when the app is backgrounded (Android blocks background activity starts).
                    val dialogShown = ExternalImportResultActivity.startIfAppInForeground(
                        this@ExternalBackupImportService,
                        summary,
                    )
                    if (!dialogShown) {
                        withContext(Dispatchers.Main) {
                            showImportSummaryToast(summary)
                        }
                    }
                    showExternalImportResultNotification(source, summary)
                },
                onFailure = {
                    showResultNotification(source, CompositeResult.failure(it))
                },
            )
        }
    }

    private fun IntentJobContext.buildNotification(): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.import_backup_from_other_apps))
            .setContentText(getString(R.string.processing_))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(0)
            .setSilent(true)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                appcompatR.drawable.abc_ic_clear_material,
                applicationContext.getString(android.R.string.cancel),
                getCancelIntent(),
            )
            .build()
    }

    private fun showImportSummaryToast(summary: ExternalBackupImportSummary) {
        val failedPreview = summary.failedTitles.take(3).joinToString(", ")
        val text = buildString {
            append("Imported ")
            append(summary.favoritesImported)
            append(" favorites, ")
            append(summary.historyImported)
            append(" history")
            if (summary.failedCount > 0) {
                append("; failed ")
                append(summary.failedCount)
                if (failedPreview.isNotBlank()) {
                    append(": ")
                    append(failedPreview)
                }
            }
            if (summary.missingSourceNames.isNotEmpty()) {
                append(". Install kototoro-parsers or kotatsu-parsers-redo")
            }
            if (summary.consolidationPending) {
                append(". ").append(getString(R.string.external_import_result_consolidation_pending))
            }
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun IntentJobContext.showExternalImportResultNotification(
        source: Uri,
        summary: ExternalBackupImportSummary,
    ) {
        if (summary.failedCount == 0 && !summary.consolidationPending) {
            showResultNotification(source, CompositeResult.success())
            return
        }
        val failedText = summary.failedRecords.take(20).joinToString("\n") { record ->
            buildString {
                append(record.title)
                val sourceText = when {
                    record.expectedSourceNames.isNotEmpty() -> record.expectedSourceNames.joinToString(", ")
                    record.sourceCandidates.isNotEmpty() -> record.sourceCandidates.joinToString(", ")
                    else -> null
                }
                if (!sourceText.isNullOrBlank()) {
                    append(" -> ")
                    append(sourceText)
                }
            }
        }
        val missingSourceText = if (summary.missingSourceNames.isNotEmpty()) {
            "\nMissing sources: ${summary.missingSourceNames.joinToString(", ")}\n" +
                "Install kototoro-parsers or kotatsu-parsers-redo, then import again."
        } else {
            ""
        }
        val uninstalledText = if (summary.uninstalledSources.isNotEmpty()) {
            "\nNot installed: " + summary.uninstalledSources.joinToString(", ") { source ->
                (source.displayName ?: source.sourceKey) + " (" + source.recordCount + ")"
            }
        } else {
            ""
        }
        val consolidationText = if (summary.consolidationPending) {
            "\n" + getString(R.string.external_import_result_consolidation_pending)
        } else {
            ""
        }
        val failedHeadline = if (summary.failedCount > 0) {
            "Failed ${summary.failedCount} unmatched titles."
        } else {
            ""
        }
        val message = "Imported ${summary.favoritesImported} favorites and ${summary.historyImported} history. " +
            "$failedHeadline$missingSourceText$uninstalledText$consolidationText\n$failedText"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.import_backup_from_other_apps))
            .setContentText(
                if (summary.failedCount > 0) {
                    "Imported with ${summary.failedCount} unmatched titles"
                } else {
                    getString(R.string.external_import_result_consolidation_pending)
                },
            )
            .setSmallIcon(R.drawable.ic_stat_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(0)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntentCompat.getActivity(
                    applicationContext,
                    0,
                    ExternalImportResultActivity.createIntent(applicationContext, summary),
                    0,
                    false,
                ),
            )
            .setBigText(getString(R.string.import_backup_from_other_apps), message)
            .build()
        notificationManager.notify(notificationTag, startId, notification)
    }

    companion object {
        private const val TAG = "EXTERNAL_BACKUP_IMPORT"
        private const val FOREGROUND_NOTIFICATION_ID = 40
        private const val EXTRA_APP = "external_backup_app"

        @CheckResult
        fun start(context: Context, uri: Uri, app: ExternalBackupApp): Boolean = try {
            val intent = Intent(context, ExternalBackupImportService::class.java)
            intent.putExtra(AppRouter.KEY_DATA, uri.toString())
            intent.putExtra(EXTRA_APP, app.name)
            intent.setData(uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            e.printStackTraceDebug()
            false
        }
    }
}

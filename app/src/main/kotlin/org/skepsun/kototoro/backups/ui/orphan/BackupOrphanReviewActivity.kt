package org.skepsun.kototoro.backups.ui.orphan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupOrphanInfo
import org.skepsun.kototoro.backups.domain.BackupOrphanReport
import org.skepsun.kototoro.backups.ui.backup.BackupService
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class BackupOrphanReviewActivity : BaseComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT)?.let { rawReport ->
            runCatching { json.decodeFromString<BackupOrphanReport>(rawReport) }.getOrNull()
        }
        val destination = intent.getStringExtra(EXTRA_DESTINATION)?.let(Uri::parse)
        if (report == null || report.items.isEmpty() || destination == null) {
            finish()
            return
        }
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        cancelReviewNotification(notificationId)
        setComposeContent {
            var isStarting by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!isStarting) finishAfterTransition() },
                title = { Text(stringResource(R.string.backup_orphan_review_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.backup_orphan_dialog_message),
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            items(report.items, key = { it.entityId }) { item ->
                                BackupOrphanRow(item)
                            }
                        }
                        if (report.totalCount > report.items.size) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.backup_orphan_more_items,
                                    report.totalCount - report.items.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.backup_orphan_dialog_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !isStarting,
                        onClick = {
                            isStarting = true
                            val started = BackupService.start(
                                context = this@BackupOrphanReviewActivity,
                                uri = destination,
                                allowDiscardingActiveWorkState = true,
                            )
                            if (started) {
                                finishAfterTransition()
                            } else {
                                isStarting = false
                                Toast.makeText(
                                    this@BackupOrphanReviewActivity,
                                    R.string.operation_not_supported,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.backup_orphan_discard_and_continue))
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isStarting,
                        onClick = ::finishAfterTransition,
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    private fun cancelReviewNotification(notificationId: Int) {
        if (notificationId != 0) {
            NotificationManagerCompat.from(this).cancel(BackupService.NOTIFICATION_TAG, notificationId)
        }
    }

    companion object {

        private const val EXTRA_REPORT = "backup_orphan_report"
        private const val EXTRA_DESTINATION = "backup_orphan_destination"
        private const val EXTRA_NOTIFICATION_ID = "backup_orphan_notification_id"
        private val json = Json { ignoreUnknownKeys = true }

        fun newIntent(
            context: Context,
            destination: Uri,
            report: BackupOrphanReport,
            notificationId: Int,
        ): Intent {
            return Intent(context, BackupOrphanReviewActivity::class.java)
                .putExtra(EXTRA_REPORT, json.encodeToString(report))
                .putExtra(EXTRA_DESTINATION, destination.toString())
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /**
         * Shows the review immediately when the app is visible. Android blocks this
         * background launch in other cases, where the backup service uses a notification.
         */
        fun startIfAppInForeground(
            context: Context,
            destination: Uri,
            report: BackupOrphanReport,
            notificationId: Int,
        ): Boolean {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return false
            }
            return try {
                context.startActivity(newIntent(context, destination, report, notificationId))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

@Composable
private fun BackupOrphanRow(item: BackupOrphanInfo) {
    val title = item.title ?: stringResource(R.string.backup_orphan_unknown_work)
    val context = LocalContext.current
    val stateText = item.stateKinds.joinToString(", ") { kind ->
        when (kind) {
            BackupOrphanInfo.StateKind.HISTORY -> context.getString(R.string.backup_orphan_state_history)
            BackupOrphanInfo.StateKind.FAVOURITE -> context.getString(R.string.backup_orphan_state_favourite)
            BackupOrphanInfo.StateKind.STATISTICS -> context.getString(R.string.backup_orphan_state_statistics)
        }
    }
    val sourceText = item.source?.let { context.getString(R.string.backup_orphan_source, it) }
    val detail = buildString {
        append(stateText)
        sourceText?.let {
            append(" · ")
            append(it)
        }
        append(" · entity_id=")
        append(item.entityId)
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = detail, style = MaterialTheme.typography.bodySmall)
    }
}

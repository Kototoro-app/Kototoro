package org.skepsun.kototoro.core.util

import android.util.Log

enum class BackupFlow(val wireName: String) {
    PERIODICAL_BACKUP("periodical_backup"),
    WEBDAV_AUTO_SYNC_UPLOAD("webdav_auto_sync_upload"),
    WEBDAV_AUTO_RESTORE("webdav_auto_restore"),
}

fun logSyncFlow(
    tag: String,
    event: String,
    reason: String? = null,
    vararg details: Pair<String, Any?>,
) {
    Log.d(tag, buildString {
        append("sync_flow event=")
        append(event)
        appendFlowDetails(reason, details)
    })
}

fun logBackupFlow(
    tag: String,
    flow: BackupFlow,
    event: String,
    reason: String? = null,
    vararg details: Pair<String, Any?>,
) {
    Log.d(tag, buildString {
        append("backup_flow flow=")
        append(flow.wireName)
        append(" event=")
        append(event)
        appendFlowDetails(reason, details)
    })
}

private fun StringBuilder.appendFlowDetails(
    reason: String?,
    details: Array<out Pair<String, Any?>>,
) {
    if (!reason.isNullOrBlank()) {
        append(' ')
        append("reason=")
        append(reason)
    }
    details.forEach { (key, value) ->
        if (value != null) {
            append(' ')
            append(key)
            append('=')
            append(value)
        }
    }
}

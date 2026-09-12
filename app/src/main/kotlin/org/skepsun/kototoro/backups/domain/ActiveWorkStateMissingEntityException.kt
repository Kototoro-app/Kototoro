package org.skepsun.kototoro.backups.domain

class ActiveWorkStateMissingEntityException(
    val report: BackupOrphanReport,
    val operation: String = "backup creation",
) : IllegalStateException(buildMessage(operation, report)) {

    private companion object {

        fun buildMessage(operation: String, report: BackupOrphanReport): String {
            val details = report.items.joinToString { item ->
                val title = item.title ?: "unknown work"
                "$title (entity_id=${item.entityId})"
            }
            val suffix = if (report.totalCount > report.items.size) {
                "; ${report.totalCount - report.items.size} more"
            } else {
                ""
            }
            return "Refusing $operation: backup snapshot has work state with missing entity ids: $details$suffix"
        }
    }
}

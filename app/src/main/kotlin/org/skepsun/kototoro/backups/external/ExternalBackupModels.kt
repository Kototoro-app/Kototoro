package org.skepsun.kototoro.backups.external

import kotlinx.serialization.Serializable
import org.skepsun.kototoro.parsers.model.ContentType

data class ExternalBackupPayload(
    val records: List<ExternalBackupContentRecord>,
    val favoriteCategories: List<ExternalBackupFavoriteCategoryRecord> = emptyList(),
)

data class ExternalBackupFavoriteCategoryRecord(
    val name: String,
    val order: Long,
    val id: Long,
    val flags: Long = 0,
)

@Serializable
data class ExternalBackupContentRecord(
    val app: ExternalBackupApp,
    val sourceName: String,
    val contentType: ContentType,
    val url: String,
    val title: String,
    val authors: String?,
    val description: String?,
    val tags: List<String>,
    val coverUrl: String?,
    val publicUrl: String,
    val state: String?,
    val isFavorite: Boolean,
    val favoriteTimestamp: Long?,
    val favoriteCategoryOrders: List<Long>,
    val chaptersCount: Int,
    val readEntriesCount: Int,
    val progressPercent: Float?,
    val historyChapterUrl: String?,
    val historyTimestamp: Long?,
    val sourceCandidates: List<String> = emptyList(),
    /**
     * Human-readable source name taken from the backup's own source registry
     * (`backupSources`), when available. Only used for reporting unmatched sources;
     * the persisted binding key stays [sourceName] (e.g. `MIHON_<id>`).
     */
    val sourceDisplayName: String? = null,
)

@Serializable
data class ExternalBackupImportSummary(
    val favoritesImported: Int,
    val historyImported: Int,
    val failedCount: Int = 0,
    val failedTitles: List<String> = emptyList(),
    val failedRecords: List<ExternalBackupFailedRecord> = emptyList(),
    val missingSourceNames: List<String> = emptyList(),
    /** Sources referenced by the backup but not installed/importable, with record counts. */
    val uninstalledSources: List<ExternalBackupUninstalledSource> = emptyList(),
)

@Serializable
data class ExternalBackupUninstalledSource(
    /** Persisted binding key, e.g. `MIHON_123456789`. */
    val sourceKey: String,
    /** Human-readable name from the backup's source registry, when known. */
    val displayName: String? = null,
    val recordCount: Int = 0,
)

@Serializable
data class ExternalBackupFailedRecord(
    val title: String,
    val sourceCandidates: List<String> = emptyList(),
    val expectedSourceNames: List<String> = emptyList(),
)

package org.skepsun.kototoro.favourites.ui.migration


import org.skepsun.kototoro.core.prefs.TrackingMetadataSourceStrategy
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.data.FavouriteContent
import org.skepsun.kototoro.favourites.domain.DEFAULT_FUZZY_MERGE_THRESHOLD
import org.skepsun.kototoro.favourites.domain.MigrationProgress
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.OrganizableWork
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.entitygraph.domain.EntityGraphRepairIssueKind
import org.skepsun.kototoro.entitygraph.domain.EntityGraphRepairReport
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

enum class EntityOrganizeStage {
    MERGE,
    TRACKING,
    READING,
}

enum class EntityOrganizeFeedbackKind {
    PREVIEW,
    EXECUTE,
}

data class EntityOrganizeFeedback(
    val stage: EntityOrganizeStage,
    val kind: EntityOrganizeFeedbackKind,
    val message: String,
)

data class EntityOrganizeCloseResult(
    val shouldRefreshFavorites: Boolean,
    val message: String?,
)

data class EntityOrganizeStagePlan(
    val stage: EntityOrganizeStage,
    val enabled: Boolean,
    val canPreview: Boolean,
    val canExecute: Boolean,
    val previewCount: Int,
    val acceptedCount: Int,
    val feedback: EntityOrganizeFeedback?,
)

data class EntityOrganizeDatasetStatus(
    val isLoading: Boolean = true,
    val summary: String = "",
    val version: String? = null,
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false,
    val sizeBytes: Long = 0L,
    val entryCount: Int = 0,
    val isInstalled: Boolean = false,
    val hasSearchIndex: Boolean = false,
    val searchIndexVersion: String? = null,
    val searchIndexEntries: Int = 0,
    val downloadProgress: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressIsCount: Boolean = false,
)

enum class EntityOrganizeDatasetBridge {
    ANIME_OFFLINE,
    MANGABAKA,
}

data class MigrationUiState(
    val favouriteSources: List<ContentSource> = emptyList(),
    val availableSources: List<ContentSource> = emptyList(),
    val selectedContentIds: Set<Long> = emptySet(),
    val organizableWorks: List<OrganizableWork> = emptyList(),
    val scopedFavouriteContents: List<FavouriteContent> = emptyList(),
    val mergeCandidateGroups: List<MergeCandidateGroup> = emptyList(),
    val mergePreviewReady: Boolean = false,
    val selectedMergeGroupIds: Set<String> = emptySet(),
    val selectedMergeItemsByGroup: Map<String, Set<Long>> = emptyMap(),
    val selectedManualMergeMangaIds: Set<Long> = emptySet(),
    val fuzzyMergeCandidatesEnabled: Boolean = false,
    val fuzzyMergeThresholdPercent: Int = (DEFAULT_FUZZY_MERGE_THRESHOLD * 100).toInt(),
    val fuzzyTrackingCandidatesEnabled: Boolean = false,
    val fuzzyTrackingThresholdPercent: Int = (DEFAULT_FUZZY_MERGE_THRESHOLD * 100).toInt(),
    val availableTrackingServices: List<ScrobblerService> = emptyList(),
    val selectedTrackingServices: List<ScrobblerService> = emptyList(),
    val trackingMetadataSourceStrategy: TrackingMetadataSourceStrategy = TrackingMetadataSourceStrategy.LOCAL_THEN_API,
    val existingTrackingPreviews: List<TrackingBindingPreview> = emptyList(),
    val trackingPreviews: List<TrackingBindingPreview> = emptyList(),
    val trackingPreviewReady: Boolean = false,
    val selectedTrackingPreviewIds: Set<String> = emptySet(),
    val readingSourcePreviews: List<ReadingSourcePreview> = emptyList(),
    val acceptedReadingPreviewIds: Set<Long> = emptySet(),
    val stageFeedbacks: Map<EntityOrganizeStage, EntityOrganizeFeedback> = emptyMap(),
    val selectedFromSource: ContentSource? = null,
    val selectedTargetSources: List<ContentSource> = emptyList(),
    val fromContentTypeFilter: Set<BrowseGroupTab> = emptySet(),
    val fromSourceTagFilter: Set<SourceTag> = emptySet(),
    val toContentTypeFilter: Set<BrowseGroupTab> = emptySet(),
    val toSourceTagFilter: Set<SourceTag> = emptySet(),
    val concurrency: Int = 3,
    val trackingProgress: MigrationProgress? = null,
    val migrationProgress: MigrationProgress? = null,
    val isExecuting: Boolean = false,
    val workId: String? = null,
    val isFinished: Boolean = false,
    val fromFilteredSources: List<ContentSource> = emptyList(),
    val toFilteredSources: List<ContentSource> = emptyList(),
    val animeDatasetStatus: EntityOrganizeDatasetStatus = EntityOrganizeDatasetStatus(),
    val mangaBakaDatasetStatus: EntityOrganizeDatasetStatus = EntityOrganizeDatasetStatus(
        isLoading = false,
        summary = "",
    ),
    val repairReport: EntityGraphRepairReport? = null,
    val isLoadingRepairReport: Boolean = true,
    val isEntityResetRunning: Boolean = false,
    val entityResetFeedback: String? = null,
    val isEntityResetConfirmationPending: Boolean = false,
) {
    val suspectMismergedLocalMangaIds: Set<Long>
        get() = repairReport
            ?.issues
            .orEmpty()
            .asSequence()
            .filter { it.kind == EntityGraphRepairIssueKind.SUSPECT_MISMERGED_LOCAL_WORK }
            .mapNotNull { it.externalId?.toLongOrNull() }
            .toSet()

    val manualMergeMangaIds: Set<Long>
        get() = selectedManualMergeMangaIds

    val hasManualSelection: Boolean
        get() = selectedContentIds.isNotEmpty()

    fun feedbackOf(stage: EntityOrganizeStage): EntityOrganizeFeedback? = stageFeedbacks[stage]

    fun stagePlan(stage: EntityOrganizeStage): EntityOrganizeStagePlan {
        return when (stage) {
            EntityOrganizeStage.MERGE -> EntityOrganizeStagePlan(
                stage = stage,
                enabled = true,
                canPreview = !isExecuting,
                canExecute = mergePreviewReady &&
                    mergeCandidateGroups.any {
                        it.id in selectedMergeGroupIds && it.isExecutableMergeCandidate()
                    } &&
                    !isExecuting,
                previewCount = mergeCandidateGroups.count { it.isExecutableMergeCandidate() },
                acceptedCount = mergeCandidateGroups.count {
                    it.id in selectedMergeGroupIds && it.isExecutableMergeCandidate()
                },
                feedback = feedbackOf(stage),
            )

            EntityOrganizeStage.TRACKING -> EntityOrganizeStagePlan(
                stage = stage,
                enabled = true,
                canPreview =
                    selectedTrackingServices.isNotEmpty() &&
                    !isExecuting,
                canExecute =
                    trackingPreviewReady &&
                    selectedTrackingPreviewIds.isNotEmpty() &&
                    !isExecuting,
                previewCount = trackingPreviews.size,
                acceptedCount = trackingPreviews.count { it.previewId in selectedTrackingPreviewIds },
                feedback = feedbackOf(stage),
            )

            EntityOrganizeStage.READING -> {
                val hasScope = hasManualSelection || selectedFromSource != null
                EntityOrganizeStagePlan(
                    stage = stage,
                    enabled = true,
                    canPreview =
                        hasScope &&
                        selectedTargetSources.isNotEmpty() &&
                        !isExecuting,
                    canExecute =
                        hasScope &&
                        selectedTargetSources.isNotEmpty() &&
                        acceptedReadingPreviewIds.isNotEmpty() &&
                        !isExecuting,
                    previewCount = readingSourcePreviews.size,
                    acceptedCount = acceptedReadingPreviewIds.size,
                    feedback = feedbackOf(stage),
                )
            }
        }
    }
}


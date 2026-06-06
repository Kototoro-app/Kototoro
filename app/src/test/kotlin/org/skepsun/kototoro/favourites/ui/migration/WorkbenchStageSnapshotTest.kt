package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MergeCandidateItem
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityWorkbenchRow
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStageFilters
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStageState
import org.skepsun.kototoro.favourites.ui.migration.compose.matchesStageFilters
import org.skepsun.kototoro.favourites.ui.migration.compose.nextActionRequiredGroupId
import org.skepsun.kototoro.favourites.ui.migration.compose.stageSnapshot
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

class WorkbenchStageSnapshotTest {

    @Test
    fun `stage snapshot marks missing tracking and reading when nothing selected`() {
        val row = sampleRow(
            groupId = "group-1",
            trackingConfidence = 0.96f,
            includeReading = true,
        )

        val snapshot = row.stageSnapshot(MigrationUiState())

        assertEquals(false, snapshot.mergeSelected)
        assertEquals(WorkbenchStageState.MISSING, snapshot.trackingState)
        assertEquals(WorkbenchStageState.MISSING, snapshot.readingState)
    }

    @Test
    fun `stage snapshot marks warning when selected tracking is low confidence`() {
        val row = sampleRow(
            groupId = "group-2",
            trackingConfidence = 0.72f,
            includeReading = false,
        )
        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("group-2"),
            selectedTrackingPreviewIds = setOf("tracking-group-2"),
        )

        val snapshot = row.stageSnapshot(uiState)

        assertEquals(true, snapshot.mergeSelected)
        assertEquals(WorkbenchStageState.WARNING, snapshot.trackingState)
        assertEquals(WorkbenchStageState.EMPTY, snapshot.readingState)
    }

    @Test
    fun `next action group id cycles through action rows`() {
        val first = sampleRow(
            groupId = "group-1",
            trackingConfidence = 0.95f,
            includeReading = true,
        )
        val second = sampleRow(
            groupId = "group-2",
            trackingConfidence = 0.92f,
            includeReading = false,
        )
        val clean = sampleRow(
            groupId = "group-3",
            trackingConfidence = 0.93f,
            includeReading = true,
        )
        val uiState = MigrationUiState(
            selectedTrackingPreviewIds = setOf("tracking-group-3"),
            acceptedReadingPreviewIds = setOf(101L),
        )

        val nextFromNone = nextActionRequiredGroupId(
            rows = listOf(first, second, clean),
            uiState = uiState,
            currentGroupId = null,
        )
        val nextFromFirst = nextActionRequiredGroupId(
            rows = listOf(first, second, clean),
            uiState = uiState,
            currentGroupId = "group-1",
        )

        assertEquals("group-1", nextFromNone)
        assertEquals("group-2", nextFromFirst)
    }

    @Test
    fun `stage filters can isolate warning tracking rows`() {
        val warningRow = sampleRow(
            groupId = "group-warning",
            trackingConfidence = 0.72f,
            includeReading = false,
        )
        val readyRow = sampleRow(
            groupId = "group-ready",
            trackingConfidence = 0.96f,
            includeReading = false,
        )
        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("group-warning", "group-ready"),
            selectedTrackingPreviewIds = setOf("tracking-group-warning", "tracking-group-ready"),
        )
        val filters = WorkbenchStageFilters(
            tracking = setOf(WorkbenchStageState.WARNING),
        )

        assertEquals(true, warningRow.matchesStageFilters(uiState, filters))
        assertEquals(false, readyRow.matchesStageFilters(uiState, filters))
    }

    @Test
    fun `stage filters can require merge ready and reading missing together`() {
        val row = sampleRow(
            groupId = "group-merge-reading",
            trackingConfidence = 0.95f,
            includeReading = true,
        )
        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("group-merge-reading"),
            selectedTrackingPreviewIds = setOf("tracking-group-merge-reading"),
        )
        val filters = WorkbenchStageFilters(
            merge = setOf(WorkbenchStageState.READY),
            reading = setOf(WorkbenchStageState.MISSING),
        )

        assertEquals(true, row.matchesStageFilters(uiState, filters))
    }

    private fun sampleRow(
        groupId: String,
        trackingConfidence: Float,
        includeReading: Boolean,
    ): EntityWorkbenchRow {
        val group = MergeCandidateGroup(
            id = groupId,
            title = "Title $groupId",
            normalizedTitle = "title$groupId",
            contentType = ContentType.MANGA,
            mangaIds = setOf(101L),
            items = listOf(
                MergeCandidateItem(
                    mangaId = 101L,
                    title = "Title A",
                    normalizedTitle = "titlea",
                    sourceName = "SOURCE_A",
                    coverUrl = null,
                    score = 1f,
                ),
            ),
            matchScore = 0.91f,
            isExactMatch = false,
        )
        val tracking = listOf(
            TrackingBindingPreview(
                previewId = "tracking-$groupId",
                groupId = groupId,
                title = group.title,
                contentTypeName = group.contentType.name,
                service = ScrobblerService.ANILIST,
                remoteId = 5001L,
                matchedTitle = "Remote $groupId",
                matchedAltTitle = null,
                url = null,
                confidence = trackingConfidence,
                matchedBy = TrackingBindingMatchKind.ONLINE_SEARCH,
                year = 2024,
                details = TrackingSiteItemDetails(
                    service = ScrobblerService.ANILIST,
                    remoteId = 5001L,
                    title = "Remote $groupId",
                    altTitle = null,
                    url = null,
                    coverUrl = null,
                    contentType = ContentType.MANGA,
                    year = 2024,
                    score = null,
                    description = null,
                    rank = null,
                    tags = emptyList(),
                    authors = emptyList(),
                    staff = emptyList(),
                    totalEpisodes = null,
                    infoboxProperties = emptyList(),
                    episodes = emptyList(),
                    characters = emptyList(),
                    commentThreads = emptyList(),
                    reviews = emptyList(),
                    relatedWorks = emptyList(),
                    recommendations = emptyList(),
                    extraSections = emptyList(),
                    actions = emptyList(),
                ),
            ),
        )
        val reading = if (includeReading) {
            listOf(
                ReadingSourcePreview(
                    mangaId = 101L,
                    title = "Title A",
                    targetSourceName = "MangaDex",
                    targetContentId = 9001L,
                    matchedTitle = "Title A Remote",
                ),
            )
        } else {
            emptyList()
        }
        return EntityWorkbenchRow(
            group = group,
            trackingCandidates = tracking,
            readingCandidates = reading,
        )
    }
}

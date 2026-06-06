package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MergeCandidateItem
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityWorkbenchRow
import org.skepsun.kototoro.favourites.ui.migration.compose.buildWorkbenchStagePageSummary
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

class WorkbenchStagePageSummaryTest {

    @Test
    fun `merge stage page summary counts selected and pending rows`() {
        val rows = listOf(
            row(groupId = "merge-selected", trackingConfidence = 0.96f, includeReading = false),
            row(groupId = "merge-pending", trackingConfidence = 0.96f, includeReading = false),
        )
        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("merge-selected"),
        )

        val summary = buildWorkbenchStagePageSummary(
            stage = EntityOrganizeStage.MERGE,
            rows = rows,
            uiState = uiState,
        )

        assertEquals(2, summary.visibleRowCount)
        assertEquals(2, summary.candidateRowCount)
        assertEquals(1, summary.selectedRowCount)
        assertEquals(1, summary.pendingRowCount)
        assertEquals(1, summary.readyRowCount)
        assertEquals(0, summary.warningRowCount)
        assertEquals(0, summary.emptyRowCount)
    }

    @Test
    fun `tracking stage page summary separates pending warning ready and empty`() {
        val rows = listOf(
            row(groupId = "tracking-pending", trackingConfidence = 0.96f, includeReading = false),
            row(groupId = "tracking-warning", trackingConfidence = 0.72f, includeReading = false),
            row(groupId = "tracking-ready", trackingConfidence = 0.96f, includeReading = false),
            rowWithoutTracking(groupId = "tracking-empty"),
        )
        val uiState = MigrationUiState(
            selectedTrackingPreviewIds = setOf("tracking-tracking-warning", "tracking-tracking-ready"),
        )

        val summary = buildWorkbenchStagePageSummary(
            stage = EntityOrganizeStage.TRACKING,
            rows = rows,
            uiState = uiState,
        )

        assertEquals(4, summary.visibleRowCount)
        assertEquals(3, summary.candidateRowCount)
        assertEquals(2, summary.selectedRowCount)
        assertEquals(1, summary.pendingRowCount)
        assertEquals(1, summary.warningRowCount)
        assertEquals(1, summary.readyRowCount)
        assertEquals(1, summary.emptyRowCount)
    }

    @Test
    fun `reading stage page summary counts accepted and missing candidates`() {
        val rows = listOf(
            row(groupId = "reading-ready", trackingConfidence = 0.96f, includeReading = true, mangaId = 401L),
            row(groupId = "reading-pending", trackingConfidence = 0.96f, includeReading = true, mangaId = 402L),
            row(groupId = "reading-empty", trackingConfidence = 0.96f, includeReading = false, mangaId = 403L),
        )
        val uiState = MigrationUiState(
            acceptedReadingPreviewIds = setOf(401L),
        )

        val summary = buildWorkbenchStagePageSummary(
            stage = EntityOrganizeStage.READING,
            rows = rows,
            uiState = uiState,
        )

        assertEquals(3, summary.visibleRowCount)
        assertEquals(2, summary.candidateRowCount)
        assertEquals(1, summary.selectedRowCount)
        assertEquals(1, summary.pendingRowCount)
        assertEquals(1, summary.readyRowCount)
        assertEquals(0, summary.warningRowCount)
        assertEquals(1, summary.emptyRowCount)
    }

    private fun row(
        groupId: String,
        trackingConfidence: Float,
        includeReading: Boolean,
        mangaId: Long = 101L,
    ): EntityWorkbenchRow {
        return EntityWorkbenchRow(
            group = MergeCandidateGroup(
                id = groupId,
                title = groupId,
                normalizedTitle = groupId,
                contentType = ContentType.MANGA,
                mangaIds = setOf(mangaId),
                items = listOf(
                    MergeCandidateItem(
                        mangaId = mangaId,
                        title = groupId,
                        normalizedTitle = groupId,
                        sourceName = "SOURCE_$groupId",
                        coverUrl = null,
                        score = trackingConfidence,
                    ),
                ),
                matchScore = trackingConfidence,
                isExactMatch = false,
            ),
            trackingCandidates = listOf(
                TrackingBindingPreview(
                    previewId = "tracking-$groupId",
                    groupId = groupId,
                    title = groupId,
                    contentTypeName = ContentType.MANGA.name,
                    service = ScrobblerService.ANILIST,
                    remoteId = mangaId,
                    matchedTitle = groupId,
                    matchedAltTitle = null,
                    url = null,
                    confidence = trackingConfidence,
                    matchedBy = TrackingBindingMatchKind.ONLINE_SEARCH,
                    year = 2024,
                    details = TrackingSiteItemDetails(
                        service = ScrobblerService.ANILIST,
                        remoteId = mangaId,
                        title = groupId,
                        altTitle = null,
                        coverUrl = null,
                        contentType = ContentType.MANGA,
                        description = null,
                        score = null,
                        rank = null,
                        tags = emptyList(),
                        authors = emptyList(),
                        staff = emptyList(),
                        year = 2024,
                        totalEpisodes = null,
                        url = null,
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
            ),
            readingCandidates = if (includeReading) {
                listOf(
                    ReadingSourcePreview(
                        mangaId = mangaId,
                        title = groupId,
                        targetSourceName = "Reading $groupId",
                        targetContentId = mangaId + 1000,
                        matchedTitle = "$groupId reading",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    private fun rowWithoutTracking(
        groupId: String,
        mangaId: Long = 201L,
    ): EntityWorkbenchRow {
        return EntityWorkbenchRow(
            group = MergeCandidateGroup(
                id = groupId,
                title = groupId,
                normalizedTitle = groupId,
                contentType = ContentType.MANGA,
                mangaIds = setOf(mangaId),
                items = listOf(
                    MergeCandidateItem(
                        mangaId = mangaId,
                        title = groupId,
                        normalizedTitle = groupId,
                        sourceName = "SOURCE_$groupId",
                        coverUrl = null,
                        score = 1f,
                    ),
                ),
                matchScore = 1f,
                isExactMatch = true,
            ),
            trackingCandidates = emptyList(),
            readingCandidates = emptyList(),
        )
    }
}

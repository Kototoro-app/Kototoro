package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MergeCandidateItem
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityWorkbenchRow
import org.skepsun.kototoro.favourites.ui.migration.compose.buildWorkbenchSelectionSummary
import org.skepsun.kototoro.favourites.ui.migration.compose.needsAction
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

class WorkbenchSelectionSummaryTest {

    @Test
    fun `row needs action when tracking exists but no tracking selection`() {
        val row = sampleRow(
            groupId = "group-1",
            trackingConfidence = 0.92f,
            withReading = false,
        )

        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("group-1"),
        )

        assertTrue(row.needsAction(uiState))
    }

    @Test
    fun `row with selected tracking and selected reading can be clean`() {
        val row = sampleRow(
            groupId = "group-1",
            trackingConfidence = 0.92f,
            withReading = true,
        )

        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("group-1"),
            selectedTrackingPreviewIds = setOf("tracking-group-1"),
            acceptedReadingPreviewIds = setOf(101L),
        )

        assertFalse(row.needsAction(uiState))
    }

    @Test
    fun `summary counts selected rows and action required rows from filtered set`() {
        val first = sampleRow(
            groupId = "group-1",
            trackingConfidence = 0.92f,
            withReading = true,
        )
        val second = sampleRow(
            groupId = "group-2",
            trackingConfidence = 0.62f,
            withReading = false,
        )
        val rows = listOf(first, second)
        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("group-1", "group-2"),
            selectedTrackingPreviewIds = setOf("tracking-group-1"),
            acceptedReadingPreviewIds = setOf(101L),
        )

        val summary = buildWorkbenchSelectionSummary(
            rows = rows,
            filteredRows = rows,
            uiState = uiState,
        )

        assertEquals(2, summary.totalRows)
        assertEquals(2, summary.visibleRows)
        assertEquals(1, summary.actionRequiredRows)
        assertEquals(2, summary.selectedRows)
        assertEquals(2, summary.selectedGroups)
        assertEquals(1, summary.selectedTracking)
        assertEquals(1, summary.selectedReading)
    }

    private fun sampleRow(
        groupId: String,
        trackingConfidence: Float,
        withReading: Boolean,
    ): EntityWorkbenchRow {
        val group = MergeCandidateGroup(
            id = groupId,
            title = "Sample $groupId",
            normalizedTitle = "sample$groupId",
            contentType = ContentType.MANGA,
            mangaIds = setOf(101L, 102L),
            items = listOf(
                MergeCandidateItem(
                    mangaId = 101L,
                    title = "Sample A",
                    normalizedTitle = "samplea",
                    sourceName = "SOURCE_A",
                    coverUrl = null,
                    score = 1f,
                ),
                MergeCandidateItem(
                    mangaId = 102L,
                    title = "Sample B",
                    normalizedTitle = "sampleb",
                    sourceName = "SOURCE_B",
                    coverUrl = null,
                    score = 0.96f,
                ),
            ),
            matchScore = 0.97f,
            isExactMatch = false,
        )
        val tracking = TrackingBindingPreview(
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
        )
        val reading = if (withReading) {
            listOf(
                ReadingSourcePreview(
                    mangaId = 101L,
                    title = "Sample A",
                    targetSourceName = "MangaDex",
                    targetContentId = 9001L,
                    matchedTitle = "Sample A Remote",
                ),
            )
        } else {
            emptyList()
        }
        return EntityWorkbenchRow(
            group = group,
            trackingCandidates = listOf(tracking),
            readingCandidates = reading,
        )
    }
}

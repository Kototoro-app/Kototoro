package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MergeCandidateItem
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityWorkbenchRow
import org.skepsun.kototoro.favourites.ui.migration.compose.buildStageWorkspaceSummaries
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

class StageWorkspaceSummaryTest {

    @Test
    fun `workspace summaries count pending warning and ready rows per stage`() {
        val rows = listOf(
            row(
                groupId = "missing-all",
                trackingConfidence = 0.92f,
                hasReading = true,
            ),
            row(
                groupId = "tracking-warning",
                trackingConfidence = 0.6f,
                hasReading = false,
            ),
            row(
                groupId = "ready-all",
                trackingConfidence = 0.96f,
                hasReading = true,
            ),
        )
        val uiState = MigrationUiState(
            selectedMergeGroupIds = setOf("tracking-warning", "ready-all"),
            selectedTrackingPreviewIds = setOf("tracking-tracking-warning", "tracking-ready-all"),
            acceptedReadingPreviewIds = setOf(301L),
        )

        val summaries = buildStageWorkspaceSummaries(rows, uiState).associateBy { it.stage }

        assertEquals(1, summaries.getValue(EntityOrganizeStage.MERGE).pendingCount)
        assertEquals(2, summaries.getValue(EntityOrganizeStage.MERGE).readyCount)

        assertEquals(1, summaries.getValue(EntityOrganizeStage.TRACKING).pendingCount)
        assertEquals(1, summaries.getValue(EntityOrganizeStage.TRACKING).warningCount)
        assertEquals(1, summaries.getValue(EntityOrganizeStage.TRACKING).readyCount)

        assertEquals(1, summaries.getValue(EntityOrganizeStage.READING).pendingCount)
        assertEquals(1, summaries.getValue(EntityOrganizeStage.READING).readyCount)
    }

    private fun row(
        groupId: String,
        trackingConfidence: Float,
        hasReading: Boolean,
    ): EntityWorkbenchRow {
        val mangaId = when (groupId) {
            "ready-all" -> 301L
            "missing-all" -> 101L
            else -> 201L
        }
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
            readingCandidates = if (hasReading) {
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
}

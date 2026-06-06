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
import org.skepsun.kototoro.favourites.ui.migration.compose.toFocusedDetail
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

class WorkbenchFocusedDetailTest {

    @Test
    fun `focused detail reports selected counters and exact match`() {
        val row = sampleRow(
            groupId = "group-1",
            matchScore = 1.4f,
            isExactMatch = true,
        )
        val uiState = MigrationUiState(
            selectedTrackingPreviewIds = setOf("tracking-group-1-1"),
            acceptedReadingPreviewIds = setOf(101L),
        )

        val detail = row.toFocusedDetail(uiState)

        assertEquals("group-1", detail.groupId)
        assertEquals("Title group-1", detail.title)
        assertEquals(ContentType.MANGA.name, detail.contentTypeName)
        assertEquals(2, detail.projectionCount)
        assertEquals(2, detail.trackingCount)
        assertEquals(2, detail.readingCount)
        assertEquals(1, detail.selectedTrackingCount)
        assertEquals(1, detail.selectedReadingCount)
        assertEquals(100, detail.matchPercent)
        assertTrue(detail.isExactMatch)
        assertFalse(detail.needsAction)
    }

    @Test
    fun `focused detail is marked action required when reading is not accepted`() {
        val row = sampleRow(
            groupId = "group-2",
            matchScore = 0.86f,
            isExactMatch = false,
        )
        val uiState = MigrationUiState(
            selectedTrackingPreviewIds = setOf("tracking-group-2-1", "tracking-group-2-2"),
            acceptedReadingPreviewIds = emptySet(),
        )

        val detail = row.toFocusedDetail(uiState)

        assertEquals(86, detail.matchPercent)
        assertFalse(detail.isExactMatch)
        assertEquals(2, detail.selectedTrackingCount)
        assertEquals(0, detail.selectedReadingCount)
        assertTrue(detail.needsAction)
    }

    @Test
    fun `focused detail clamps score percent into 0 to 100`() {
        val highRow = sampleRow(
            groupId = "group-high",
            matchScore = 6f,
            isExactMatch = true,
        )
        val lowRow = sampleRow(
            groupId = "group-low",
            matchScore = -2f,
            isExactMatch = false,
        )

        val high = highRow.toFocusedDetail(MigrationUiState())
        val low = lowRow.toFocusedDetail(MigrationUiState())

        assertEquals(100, high.matchPercent)
        assertEquals(0, low.matchPercent)
    }

    private fun sampleRow(
        groupId: String,
        matchScore: Float,
        isExactMatch: Boolean,
    ): EntityWorkbenchRow {
        val group = MergeCandidateGroup(
            id = groupId,
            title = "Title $groupId",
            normalizedTitle = "title$groupId",
            contentType = ContentType.MANGA,
            mangaIds = setOf(101L, 102L),
            items = listOf(
                MergeCandidateItem(
                    mangaId = 101L,
                    title = "Title A",
                    normalizedTitle = "titlea",
                    sourceName = "SOURCE_A",
                    coverUrl = null,
                    score = 1f,
                ),
                MergeCandidateItem(
                    mangaId = 102L,
                    title = "Title B",
                    normalizedTitle = "titleb",
                    sourceName = "SOURCE_B",
                    coverUrl = null,
                    score = 0.92f,
                ),
            ),
            matchScore = matchScore,
            isExactMatch = isExactMatch,
        )
        val tracking = listOf(
            trackingPreview(groupId = groupId, previewId = "tracking-$groupId-1", confidence = 0.98f),
            trackingPreview(groupId = groupId, previewId = "tracking-$groupId-2", confidence = 0.91f),
        )
        val reading = listOf(
            ReadingSourcePreview(
                mangaId = 101L,
                title = "Title A",
                targetSourceName = "MangaDex",
                targetContentId = 2001L,
                matchedTitle = "Title A Remote",
            ),
            ReadingSourcePreview(
                mangaId = 102L,
                title = "Title B",
                targetSourceName = "Bangumi Books",
                targetContentId = 2002L,
                matchedTitle = "Title B Remote",
            ),
        )
        return EntityWorkbenchRow(
            group = group,
            trackingCandidates = tracking,
            readingCandidates = reading,
        )
    }

    private fun trackingPreview(
        groupId: String,
        previewId: String,
        confidence: Float,
    ): TrackingBindingPreview {
        return TrackingBindingPreview(
            previewId = previewId,
            groupId = groupId,
            title = "Title $groupId",
            contentTypeName = ContentType.MANGA.name,
            service = ScrobblerService.ANILIST,
            remoteId = previewId.hashCode().toLong(),
            matchedTitle = "Matched $previewId",
            matchedAltTitle = null,
            url = null,
            confidence = confidence,
            matchedBy = TrackingBindingMatchKind.ONLINE_SEARCH,
            year = 2024,
            details = TrackingSiteItemDetails(
                service = ScrobblerService.ANILIST,
                remoteId = previewId.hashCode().toLong(),
                title = "Matched $previewId",
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
    }
}

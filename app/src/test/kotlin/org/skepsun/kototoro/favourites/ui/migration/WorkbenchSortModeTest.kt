package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MergeCandidateItem
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityWorkbenchRow
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchSortMode
import org.skepsun.kototoro.favourites.ui.migration.compose.sortWorkbenchRows
import org.skepsun.kototoro.parsers.model.ContentType

class WorkbenchSortModeTest {

    @Test
    fun `action first sorts action rows before clean rows`() {
        val needsAction = row(
            groupId = "b",
            title = "Beta",
            matchScore = 0.62f,
            projectionCount = 2,
        )
        val clean = row(
            groupId = "a",
            title = "Alpha",
            matchScore = 0.95f,
            projectionCount = 4,
        )
        val uiState = MigrationUiState(
            selectedTrackingPreviewIds = setOf("tracking-a"),
        )

        val result = sortWorkbenchRows(
            rows = listOf(clean, needsAction),
            uiState = uiState,
            sortMode = WorkbenchSortMode.ACTION_FIRST,
        )

        assertEquals(listOf("b", "a"), result.map { it.group.id })
    }

    @Test
    fun `match score sorts descending by group score`() {
        val rows = listOf(
            row(groupId = "c", title = "Gamma", matchScore = 0.75f, projectionCount = 1),
            row(groupId = "a", title = "Alpha", matchScore = 0.97f, projectionCount = 2),
            row(groupId = "b", title = "Beta", matchScore = 0.81f, projectionCount = 3),
        )

        val result = sortWorkbenchRows(
            rows = rows,
            uiState = MigrationUiState(),
            sortMode = WorkbenchSortMode.MATCH_SCORE,
        )

        assertEquals(listOf("a", "b", "c"), result.map { it.group.id })
    }

    @Test
    fun `title sort uses alphabetical title ordering`() {
        val rows = listOf(
            row(groupId = "c", title = "Gamma", matchScore = 0.75f, projectionCount = 1),
            row(groupId = "a", title = "Alpha", matchScore = 0.97f, projectionCount = 2),
            row(groupId = "b", title = "beta", matchScore = 0.81f, projectionCount = 3),
        )

        val result = sortWorkbenchRows(
            rows = rows,
            uiState = MigrationUiState(),
            sortMode = WorkbenchSortMode.TITLE,
        )

        assertEquals(listOf("a", "b", "c"), result.map { it.group.id })
    }

    private fun row(
        groupId: String,
        title: String,
        matchScore: Float,
        projectionCount: Int,
    ): EntityWorkbenchRow {
        val mangaIds = (1..projectionCount).map { it.toLong() }.toSet()
        return EntityWorkbenchRow(
            group = MergeCandidateGroup(
                id = groupId,
                title = title,
                normalizedTitle = title.lowercase(),
                contentType = ContentType.MANGA,
                mangaIds = mangaIds,
                items = mangaIds.map { mangaId ->
                    MergeCandidateItem(
                        mangaId = mangaId,
                        title = "$title $mangaId",
                        normalizedTitle = "${title.lowercase()}$mangaId",
                        sourceName = "SOURCE_$mangaId",
                        coverUrl = null,
                        score = matchScore,
                    )
                },
                matchScore = matchScore,
                isExactMatch = matchScore >= 1f,
            ),
            trackingCandidates = listOf(
                org.skepsun.kototoro.favourites.domain.TrackingBindingPreview(
                    previewId = "tracking-$groupId",
                    groupId = groupId,
                    title = title,
                    contentTypeName = ContentType.MANGA.name,
                    service = org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService.ANILIST,
                    remoteId = groupId.hashCode().toLong(),
                    matchedTitle = "$title remote",
                    matchedAltTitle = null,
                    url = null,
                    confidence = matchScore,
                    matchedBy = org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind.ONLINE_SEARCH,
                    year = 2024,
                    details = org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails(
                        service = org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService.ANILIST,
                        remoteId = groupId.hashCode().toLong(),
                        title = "$title remote",
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
            ),
            readingCandidates = emptyList(),
        )
    }
}

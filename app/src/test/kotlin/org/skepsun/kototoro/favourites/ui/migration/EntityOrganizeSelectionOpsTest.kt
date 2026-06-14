package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MergeCandidateItem
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

class EntityOrganizeSelectionOpsTest {

    @Test
    fun `update merge group selection adds visible groups`() {
        val result = updateMergeGroupSelection(
            current = setOf("group-1"),
            groupIds = setOf("group-2", "group-3"),
            selected = true,
        )

        assertEquals(setOf("group-1", "group-2", "group-3"), result)
    }

    @Test
    fun `update merge group selection removes visible groups`() {
        val result = updateMergeGroupSelection(
            current = setOf("group-1", "group-2", "group-3"),
            groupIds = setOf("group-2"),
            selected = false,
        )

        assertEquals(setOf("group-1", "group-3"), result)
    }

    @Test
    fun `preview ids for groups resolves only previews in scope`() {
        val previews = listOf(
            trackingPreview("group-1", "preview-1"),
            trackingPreview("group-2", "preview-2"),
            trackingPreview("group-2", "preview-3"),
        )

        val ids = previewIdsForGroups(
            previews = previews,
            groupIds = setOf("group-2"),
        )

        assertEquals(setOf("preview-2", "preview-3"), ids)
    }

    @Test
    fun `clear selection ids removes only requested ids`() {
        val result = clearSelectionIds(
            current = setOf(1L, 2L, 3L),
            idsToClear = setOf(2L, 4L),
        )

        assertEquals(setOf(1L, 3L), result)
    }

    @Test
    fun `selected merge group with empty item selection executes whole group`() {
        val group = mergeGroup(
            groupId = "group-1",
            mangaIds = setOf(101L, 102L),
        )

        val selectedGroups = buildSelectedMergeGroupsForExecution(
            groups = listOf(group),
            selectedGroupIds = setOf("group-1"),
            selectedItemsByGroup = mapOf("group-1" to emptySet()),
        )

        assertEquals(listOf(group), selectedGroups)
    }

    @Test
    fun `selected merge group with one selected item is skipped`() {
        val group = mergeGroup(
            groupId = "group-1",
            mangaIds = setOf(101L, 102L),
        )

        val selectedGroups = buildSelectedMergeGroupsForExecution(
            groups = listOf(group),
            selectedGroupIds = setOf("group-1"),
            selectedItemsByGroup = mapOf("group-1" to setOf(101L)),
        )

        assertEquals(emptyList<MergeCandidateGroup>(), selectedGroups)
    }

    private fun mergeGroup(groupId: String, mangaIds: Set<Long>): MergeCandidateGroup {
        return MergeCandidateGroup(
            id = groupId,
            title = "Title $groupId",
            normalizedTitle = "title$groupId",
            contentType = ContentType.MANGA,
            mangaIds = mangaIds,
            items = mangaIds.map { mangaId ->
                MergeCandidateItem(
                    mangaId = mangaId,
                    title = "Title $mangaId",
                    normalizedTitle = "title$mangaId",
                    sourceName = "SOURCE_$mangaId",
                    coverUrl = null,
                    score = 1f,
                )
            },
            matchScore = 1f,
            isExactMatch = true,
        )
    }

    private fun trackingPreview(groupId: String, previewId: String): TrackingBindingPreview {
        return TrackingBindingPreview(
            previewId = previewId,
            groupId = groupId,
            title = "Title $groupId",
            contentTypeName = ContentType.MANGA.name,
            service = ScrobblerService.ANILIST,
            remoteId = previewId.removePrefix("preview-").toLong(),
            matchedTitle = "Matched $previewId",
            matchedAltTitle = null,
            url = null,
            confidence = 0.9f,
            matchedBy = TrackingBindingMatchKind.ONLINE_SEARCH,
            year = 2024,
            details = TrackingSiteItemDetails(
                service = ScrobblerService.ANILIST,
                remoteId = previewId.removePrefix("preview-").toLong(),
                title = "Matched $previewId",
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
        )
    }
}

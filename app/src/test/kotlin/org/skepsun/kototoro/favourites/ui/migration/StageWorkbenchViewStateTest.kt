package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityOrganizeWorkbenchViewState
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchSortMode
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStageFilters
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStageState
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStatusFilter
import org.skepsun.kototoro.favourites.ui.migration.compose.resolveStageWorkbenchViewState

class StageWorkbenchViewStateTest {

    @Test
    fun `merge stage focuses missing merge selections with projection ordering`() {
        val result = resolveStageWorkbenchViewState(
            selectedStage = EntityOrganizeStage.MERGE,
            current = EntityOrganizeWorkbenchViewState(
                query = "foo",
                showSelectedOnly = true,
                currentPage = 3,
                statusFilter = WorkbenchStatusFilter.SELECTED,
                sortMode = WorkbenchSortMode.TITLE,
                stageFilters = WorkbenchStageFilters(
                    tracking = setOf(WorkbenchStageState.WARNING),
                ),
            ),
        )

        assertFalse(result.showSelectedOnly)
        assertEquals(0, result.currentPage)
        assertEquals(WorkbenchStatusFilter.ALL, result.statusFilter)
        assertEquals(WorkbenchSortMode.PROJECTIONS, result.sortMode)
        assertEquals(setOf(WorkbenchStageState.MISSING), result.stageFilters.merge)
    }

    @Test
    fun `tracking stage focuses pending and warning tracking entities`() {
        val result = resolveStageWorkbenchViewState(
            selectedStage = EntityOrganizeStage.TRACKING,
            current = EntityOrganizeWorkbenchViewState(
                statusFilter = WorkbenchStatusFilter.ALL,
                sortMode = WorkbenchSortMode.PROJECTIONS,
            ),
        )

        assertEquals(WorkbenchStatusFilter.ACTION_REQUIRED, result.statusFilter)
        assertEquals(WorkbenchSortMode.MATCH_SCORE, result.sortMode)
        assertEquals(
            setOf(WorkbenchStageState.MISSING, WorkbenchStageState.WARNING),
            result.stageFilters.tracking,
        )
    }

    @Test
    fun `reading stage focuses pending reading entities with action ordering`() {
        val result = resolveStageWorkbenchViewState(
            selectedStage = EntityOrganizeStage.READING,
            current = EntityOrganizeWorkbenchViewState(
                statusFilter = WorkbenchStatusFilter.ALL,
                sortMode = WorkbenchSortMode.PROJECTIONS,
            ),
        )

        assertEquals(WorkbenchStatusFilter.ACTION_REQUIRED, result.statusFilter)
        assertEquals(WorkbenchSortMode.ACTION_FIRST, result.sortMode)
        assertEquals(setOf(WorkbenchStageState.MISSING), result.stageFilters.reading)
    }
}

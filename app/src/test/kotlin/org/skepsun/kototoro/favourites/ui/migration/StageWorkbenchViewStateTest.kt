package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityOrganizeWorkbenchViewState
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchSortMode
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStageFilters
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStageState
import org.skepsun.kototoro.favourites.ui.migration.compose.WorkbenchStatusFilter
import org.skepsun.kototoro.favourites.ui.migration.compose.resolveStageWorkbenchViewState

class StageWorkbenchViewStateTest {

    @Test
    fun `merge stage preserves current view state`() {
        val current = EntityOrganizeWorkbenchViewState(
            query = "foo",
            showSelectedOnly = true,
            currentPage = 3,
            statusFilter = WorkbenchStatusFilter.SELECTED,
            sortMode = WorkbenchSortMode.TITLE,
            stageFilters = WorkbenchStageFilters(
                tracking = setOf(WorkbenchStageState.WARNING),
            ),
        )

        val result = resolveStageWorkbenchViewState(
            selectedStage = EntityOrganizeStage.MERGE,
            current = current,
        )

        assertEquals(current, result)
    }

    @Test
    fun `tracking stage preserves current view state`() {
        val current = EntityOrganizeWorkbenchViewState(
            statusFilter = WorkbenchStatusFilter.ALL,
            sortMode = WorkbenchSortMode.PROJECTIONS,
        )

        val result = resolveStageWorkbenchViewState(
            selectedStage = EntityOrganizeStage.TRACKING,
            current = current,
        )

        assertEquals(current, result)
    }

    @Test
    fun `reading stage preserves current view state`() {
        val current = EntityOrganizeWorkbenchViewState(
            statusFilter = WorkbenchStatusFilter.ALL,
            sortMode = WorkbenchSortMode.PROJECTIONS,
        )

        val result = resolveStageWorkbenchViewState(
            selectedStage = EntityOrganizeStage.READING,
            current = current,
        )

        assertEquals(current, result)
    }
}

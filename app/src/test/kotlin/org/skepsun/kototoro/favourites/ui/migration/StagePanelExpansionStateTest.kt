package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.favourites.ui.migration.compose.StagePanelExpansionState
import org.skepsun.kototoro.favourites.ui.migration.compose.resolveStagePanelExpansionState

class StagePanelExpansionStateTest {

    @Test
    fun `merge stage expands only merge panel`() {
        val result = resolveStagePanelExpansionState(EntityOrganizeStage.MERGE)

        assertEquals(
            StagePanelExpansionState(
                mergeExpanded = true,
                trackingExpanded = false,
                readingExpanded = false,
            ),
            result,
        )
    }

    @Test
    fun `tracking stage expands only tracking panel`() {
        val result = resolveStagePanelExpansionState(EntityOrganizeStage.TRACKING)

        assertEquals(
            StagePanelExpansionState(
                mergeExpanded = false,
                trackingExpanded = true,
                readingExpanded = false,
            ),
            result,
        )
    }

    @Test
    fun `reading stage expands only reading panel`() {
        val result = resolveStagePanelExpansionState(EntityOrganizeStage.READING)

        assertEquals(
            StagePanelExpansionState(
                mergeExpanded = false,
                trackingExpanded = false,
                readingExpanded = true,
            ),
            result,
        )
    }
}

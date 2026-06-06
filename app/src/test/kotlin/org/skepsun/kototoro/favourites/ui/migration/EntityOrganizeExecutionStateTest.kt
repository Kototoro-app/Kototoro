package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityOrganizeExecutionStateTest {

    @Test
    fun `merge stage can execute independently from reading stage`() {
        val uiState = MigrationUiState(
            mergeEntitiesEnabled = true,
            attachReadingSourcesEnabled = false,
            selectedMergeGroupIds = setOf("group-1"),
        )

        assertTrue(uiState.stagePlan(EntityOrganizeStage.MERGE).canExecute)
        assertFalse(uiState.stagePlan(EntityOrganizeStage.READING).canExecute)
    }

    @Test
    fun `tracking stage can execute with accepted preview even when reading stage is not ready`() {
        val uiState = MigrationUiState(
            bindTrackingEnabled = true,
            selectedMergeGroupIds = setOf("group-1"),
            selectedTrackingServices = listOf(org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService.ANILIST),
            selectedTrackingPreviewIds = setOf("preview-1"),
            attachReadingSourcesEnabled = true,
        )

        assertTrue(uiState.stagePlan(EntityOrganizeStage.TRACKING).canExecute)
        assertFalse(uiState.stagePlan(EntityOrganizeStage.READING).canExecute)
    }

    @Test
    fun `reading stage still requires accepted previews before execute`() {
        val previewOnly = MigrationUiState(
            attachReadingSourcesEnabled = true,
            selectedContentIds = setOf(1L),
            selectedTargetSources = listOf(org.skepsun.kototoro.core.model.TestContentSource),
        )
        val executable = previewOnly.copy(
            acceptedReadingPreviewIds = setOf(1L),
        )

        assertFalse(previewOnly.stagePlan(EntityOrganizeStage.READING).canExecute)
        assertTrue(executable.stagePlan(EntityOrganizeStage.READING).canExecute)
    }
}

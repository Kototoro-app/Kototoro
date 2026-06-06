package org.skepsun.kototoro.favourites.ui.migration

import org.skepsun.kototoro.core.model.TestContentSource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationUiStateTest {

    @Test
    fun `merge stage executes only when merge is enabled and selected groups exist`() {
        val disabledPlan = MigrationUiState(
            mergeEntitiesEnabled = false,
            selectedMergeGroupIds = setOf("group-1"),
        ).stagePlan(EntityOrganizeStage.MERGE)
        val enabledPlan = MigrationUiState(
            mergeEntitiesEnabled = true,
            selectedMergeGroupIds = setOf("group-1"),
        ).stagePlan(EntityOrganizeStage.MERGE)

        assertFalse(disabledPlan.canExecute)
        assertTrue(enabledPlan.canExecute)
    }

    @Test
    fun `tracking stage preview requires selected entities and tracking services`() {
        val missingServices = MigrationUiState(
            bindTrackingEnabled = true,
            selectedMergeGroupIds = setOf("group-1"),
        ).stagePlan(EntityOrganizeStage.TRACKING)
        val readyToPreview = MigrationUiState(
            bindTrackingEnabled = true,
            selectedMergeGroupIds = setOf("group-1"),
            selectedTrackingServices = listOf(org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService.ANILIST),
        ).stagePlan(EntityOrganizeStage.TRACKING)

        assertFalse(missingServices.canPreview)
        assertTrue(readyToPreview.canPreview)
    }

    @Test
    fun `reading stage preview and execute require scope targets and accepted projections`() {
        val noScopePlan = MigrationUiState(
            attachReadingSourcesEnabled = true,
        ).stagePlan(EntityOrganizeStage.READING)
        val previewOnlyPlan = MigrationUiState(
            attachReadingSourcesEnabled = true,
            selectedContentIds = setOf(1L),
            selectedTargetSources = listOf(dummySource),
        ).stagePlan(EntityOrganizeStage.READING)
        val executablePlan = MigrationUiState(
            attachReadingSourcesEnabled = true,
            selectedContentIds = setOf(1L),
            selectedTargetSources = listOf(dummySource),
            acceptedReadingPreviewIds = setOf(10L),
        ).stagePlan(EntityOrganizeStage.READING)

        assertFalse(noScopePlan.canPreview)
        assertTrue(previewOnlyPlan.canPreview)
        assertFalse(previewOnlyPlan.canExecute)
        assertTrue(executablePlan.canExecute)
    }

    private companion object {
        val dummySource = TestContentSource
    }
}

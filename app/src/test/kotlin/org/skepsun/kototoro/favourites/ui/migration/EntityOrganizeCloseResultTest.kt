package org.skepsun.kototoro.favourites.ui.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityOrganizeCloseResultTest {

    @Test
    fun `close result stays silent when no execute feedback exists`() {
        val result = buildEntityOrganizeCloseResult(
            MigrationUiState(
                stageFeedbacks = mapOf(
                    EntityOrganizeStage.TRACKING to EntityOrganizeFeedback(
                        stage = EntityOrganizeStage.TRACKING,
                        kind = EntityOrganizeFeedbackKind.PREVIEW,
                        message = "preview only",
                    ),
                ),
            ),
        )

        assertFalse(result.shouldRefreshFavorites)
        assertEquals(null, result.message)
    }

    @Test
    fun `close result forwards single execute feedback`() {
        val result = buildEntityOrganizeCloseResult(
            MigrationUiState(
                stageFeedbacks = mapOf(
                    EntityOrganizeStage.MERGE to EntityOrganizeFeedback(
                        stage = EntityOrganizeStage.MERGE,
                        kind = EntityOrganizeFeedbackKind.EXECUTE,
                        message = "已合并 2 组，失败 0 组，跳过 1 组",
                    ),
                ),
            ),
        )

        assertTrue(result.shouldRefreshFavorites)
        assertEquals("已合并 2 组，失败 0 组，跳过 1 组", result.message)
    }

    @Test
    fun `close result summarizes multiple execute feedbacks`() {
        val result = buildEntityOrganizeCloseResult(
            MigrationUiState(
                stageFeedbacks = linkedMapOf(
                    EntityOrganizeStage.MERGE to EntityOrganizeFeedback(
                        stage = EntityOrganizeStage.MERGE,
                        kind = EntityOrganizeFeedbackKind.EXECUTE,
                        message = "merge done",
                    ),
                    EntityOrganizeStage.READING to EntityOrganizeFeedback(
                        stage = EntityOrganizeStage.READING,
                        kind = EntityOrganizeFeedbackKind.EXECUTE,
                        message = "reading done",
                    ),
                ),
            ),
        )

        assertTrue(result.shouldRefreshFavorites)
        assertEquals("已执行 2 个整理阶段，收藏视图将刷新。最近结果：reading done", result.message)
    }
}

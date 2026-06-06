package org.skepsun.kototoro.main.ui.compose

import androidx.lifecycle.SavedStateHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityOrganizeResultConsumptionTest {

    @Test
    fun `refresh result is consumed once and reset`() {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                ENTITY_ORGANIZE_RESULT_REFRESH_KEY to true,
            ),
        )

        assertTrue(consumeEntityOrganizeRefreshResult(savedStateHandle))
        assertFalse(consumeEntityOrganizeRefreshResult(savedStateHandle))
        assertEquals(false, savedStateHandle.get<Boolean>(ENTITY_ORGANIZE_RESULT_REFRESH_KEY))
    }

    @Test
    fun `message result is consumed once and cleared`() {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                ENTITY_ORGANIZE_RESULT_MESSAGE_KEY to "已执行 1 个整理阶段",
            ),
        )

        assertEquals("已执行 1 个整理阶段", consumeEntityOrganizeMessageResult(savedStateHandle))
        assertNull(consumeEntityOrganizeMessageResult(savedStateHandle))
        assertNull(savedStateHandle.get<String>(ENTITY_ORGANIZE_RESULT_MESSAGE_KEY))
    }

    @Test
    fun `blank message result is ignored and preserved as null semantic`() {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                ENTITY_ORGANIZE_RESULT_MESSAGE_KEY to "   ",
            ),
        )

        assertNull(consumeEntityOrganizeMessageResult(savedStateHandle))
        assertEquals("   ", savedStateHandle.get<String>(ENTITY_ORGANIZE_RESULT_MESSAGE_KEY))
    }
}

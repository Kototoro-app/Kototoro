package org.skepsun.kototoro.history.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HistoryRecoveryRulesTest {

    private fun recover(
        percent: Float = 0.5f,
        chapterId: Long = 99L,
        parentChapterId: Long? = null,
        isLocal: Boolean = false,
        chapterIds: List<Long>? = listOf(10L, 20L, 30L, 40L),
    ) = recoverHistoryChapterId(isLocal, chapterId, parentChapterId, percent, chapterIds)

    @Test
    fun `missing remote chapter is recovered by progress position`() {
        assertEquals(30L, recover())
        assertEquals(10L, recover(percent = 0f))
        assertEquals(40L, recover(percent = 0.99f))
    }

    @Test
    fun `present chapter and local content never require recovery`() {
        assertNull(recover(chapterId = 20L))
        assertNull(recover(isLocal = true))
    }

    @Test
    fun `missing chapters cannot be recovered`() {
        assertNull(recover(chapterIds = null))
        assertNull(recover(chapterIds = emptyList()))
    }

    @Test
    fun `distinct EPUB parent protects internal chapter identity`() {
        assertNull(recover(parentChapterId = 10L))
        assertEquals(30L, recover(parentChapterId = 99L))
    }

    @Test
    fun `out of range progress does not clamp to final chapter`() {
        for (percent in listOf(1f, 1.1f, -1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(recover(percent = percent))
        }
    }

    @Test
    fun `legacy float to index conversion is retained`() {
        assertEquals(10L, recover(percent = Float.NaN))
        assertEquals(10L, recover(percent = -0.01f))
    }
}

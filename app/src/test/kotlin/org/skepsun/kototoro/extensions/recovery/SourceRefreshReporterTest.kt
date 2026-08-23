package org.skepsun.kototoro.extensions.recovery

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.dao.SourceRefreshStateDao
import org.skepsun.kototoro.core.db.entity.SourceRefreshStateEntity

class SourceRefreshReporterTest {

    private val sourceKey = "TSUNDOKU_9001"
    private val contentId = 42L

    /**
     * Relaxed DAO mock whose `get` reflects the last upsert, simulating the read-merge-upsert
     * the reporter relies on when an existing row must be preserved.
     */
    private fun backingDao(): SourceRefreshStateDao {
        val dao = mockk<SourceRefreshStateDao>(relaxed = true)
        var stored: SourceRefreshStateEntity? = null
        coEvery { dao.get(any(), any()) } answers { stored }
        coEvery { dao.upsert(any()) } answers { stored = firstArg<SourceRefreshStateEntity>() }
        return dao
    }

    @Test
    fun `attempt creates a new row with attempt and update timestamps only`() = runTest {
        val dao = mockk<SourceRefreshStateDao>(relaxed = true)
        coEvery { dao.get(any(), any()) } returns null
        val captured = slot<SourceRefreshStateEntity>()

        RoomSourceRefreshReporter(dao).recordAttempt(sourceKey, contentId, now = 2_000L)

        coVerify(exactly = 1) { dao.upsert(capture(captured)) }
        assertEquals(2_000L, captured.captured.lastAttemptAt)
        assertEquals(2_000L, captured.captured.updatedAt)
        assertNull(captured.captured.lastSuccessAt)
        assertNull(captured.captured.lastError)
    }

    @Test
    fun `attempt preserves existing success and error fields`() = runTest {
        val dao = mockk<SourceRefreshStateDao>(relaxed = true)
        val existing = SourceRefreshStateEntity(
            sourceKey = sourceKey,
            contentId = contentId,
            lastSuccessAt = 5_000L,
            lastAttemptAt = 4_000L,
            lastError = "boom",
            updatedAt = 4_000L,
        )
        coEvery { dao.get(sourceKey, contentId) } returns existing
        val captured = slot<SourceRefreshStateEntity>()

        RoomSourceRefreshReporter(dao).recordAttempt(sourceKey, contentId, now = 6_000L)

        coVerify(exactly = 1) { dao.upsert(capture(captured)) }
        assertEquals(5_000L, captured.captured.lastSuccessAt)
        assertEquals("boom", captured.captured.lastError)
        assertEquals(6_000L, captured.captured.lastAttemptAt)
        assertEquals(6_000L, captured.captured.updatedAt)
    }

    @Test
    fun `failure after success keeps lastSuccessAt and records the error`() = runTest {
        val dao = backingDao()
        val captured = mutableListOf<SourceRefreshStateEntity>()

        RoomSourceRefreshReporter(dao).recordSuccess(sourceKey, contentId, now = 1_000L)
        RoomSourceRefreshReporter(dao).recordFailure(sourceKey, contentId, error = "boom")

        coVerify(atLeast = 2) { dao.upsert(capture(captured)) }
        assertEquals(1_000L, captured.last().lastSuccessAt)
        assertEquals("boom", captured.last().lastError)
        assertNotNull(captured.last().lastAttemptAt)
    }

    @Test
    fun `success after failure advances lastSuccessAt and clears lastError`() = runTest {
        val dao = backingDao()
        val captured = mutableListOf<SourceRefreshStateEntity>()

        RoomSourceRefreshReporter(dao).recordFailure(sourceKey, contentId, error = "boom")
        RoomSourceRefreshReporter(dao).recordSuccess(sourceKey, contentId, now = 1_000L)

        coVerify(atLeast = 1) { dao.upsert(capture(captured)) }
        assertEquals(1_000L, captured.last().lastSuccessAt)
        assertNull(captured.last().lastError)
    }

    @Test
    fun `success with no existing row creates a success row`() = runTest {
        val dao = mockk<SourceRefreshStateDao>(relaxed = true)
        coEvery { dao.get(any(), any()) } returns null
        val captured = slot<SourceRefreshStateEntity>()

        RoomSourceRefreshReporter(dao).recordSuccess(sourceKey, contentId, now = 1_000L)

        coVerify(exactly = 1) { dao.upsert(capture(captured)) }
        assertEquals(1_000L, captured.captured.lastSuccessAt)
        assertNull(captured.captured.lastAttemptAt)
        assertNull(captured.captured.lastError)
    }

    @Test
    fun `noop reporter never throws`() = runTest {
        NoOpSourceRefreshReporter.recordAttempt(sourceKey, contentId)
        NoOpSourceRefreshReporter.recordSuccess(sourceKey, contentId)
        NoOpSourceRefreshReporter.recordFailure(sourceKey, contentId, error = "boom")
        NoOpSourceRefreshReporter.recordFailure(sourceKey, contentId, error = null)
    }
}

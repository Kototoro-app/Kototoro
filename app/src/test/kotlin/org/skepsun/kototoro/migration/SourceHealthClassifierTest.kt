package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.migration.domain.SourceHealthClassifier
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.migration.domain.SourceSignals
import org.skepsun.kototoro.migration.domain.TrackSignal
import org.skepsun.kototoro.tracker.data.TrackEntity

class SourceHealthClassifierTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val ok = SourceSignals(isUnresolved = false, isBroken = false, isDisabled = false)
    private fun failed(daysAgo: Long, error: String? = "HTTP 403") =
        TrackSignal(TrackEntity.RESULT_FAILED, now - daysAgo * day, error)

    @Test
    fun `unresolved wins over everything`() {
        val signals = SourceSignals(isUnresolved = true, isBroken = true, isDisabled = true)
        assertEquals(SourceHealthStatus.UNINSTALLED, SourceHealthClassifier.classify(signals, emptyList(), now).status)
    }

    @Test
    fun `broken wins over failing and disabled`() {
        val signals = ok.copy(isBroken = true, isDisabled = true)
        assertEquals(SourceHealthStatus.BROKEN, SourceHealthClassifier.classify(signals, emptyList(), now).status)
    }

    @Test
    fun `two recent failures and no success mean failing with summary`() {
        val verdict = SourceHealthClassifier.classify(ok, listOf(failed(1), failed(3)), now)
        assertEquals(SourceHealthStatus.FAILING, verdict.status)
        assertEquals("HTTP 403", verdict.errorSummary)
    }

    @Test
    fun `a single failure is not enough`() {
        assertEquals(SourceHealthStatus.HEALTHY, SourceHealthClassifier.classify(ok, listOf(failed(1)), now).status)
    }

    @Test
    fun `a recent success clears failing`() {
        val success = TrackSignal(TrackEntity.RESULT_NO_UPDATE, now - day, null)
        assertEquals(
            SourceHealthStatus.HEALTHY,
            SourceHealthClassifier.classify(ok, listOf(failed(1), failed(2), success), now).status,
        )
    }

    @Test
    fun `failures older than fourteen days are ignored`() {
        assertEquals(
            SourceHealthStatus.HEALTHY,
            SourceHealthClassifier.classify(ok, listOf(failed(20), failed(30)), now).status,
        )
    }

    @Test
    fun `disabled is reported when nothing worse applies`() {
        assertEquals(
            SourceHealthStatus.DISABLED,
            SourceHealthClassifier.classify(ok.copy(isDisabled = true), emptyList(), now).status,
        )
    }
}

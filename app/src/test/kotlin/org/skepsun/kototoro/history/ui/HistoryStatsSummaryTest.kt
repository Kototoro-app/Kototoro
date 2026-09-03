package org.skepsun.kototoro.history.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.stats.domain.StatsDashboard

/**
 * The reading-statistics card shares the history page entry with the list's own cold
 * snapshot read; these tests pin that the secondary read never starts before the list has
 * data, and that it is subscribed exactly once.
 */
class HistoryStatsSummaryTest {

    @Test
    fun `disabled stats never read the dashboard`() = runTest {
        var reads = 0

        val summary = observeStatsSummary(
            isEnabled = flowOf(false),
            listReady = flowOf(true),
        ) {
            reads += 1
            flowOf(StatsDashboard())
        }.first()

        assertEquals(null, summary)
        assertEquals(0, reads)
    }

    @Test
    fun `dashboard is not read while the list is still loading`() = runTest {
        val listReady = MutableStateFlow(false)
        var reads = 0
        val collected = mutableListOf<StatsDashboard?>()

        val job = launch {
            observeStatsSummary(
                isEnabled = flowOf(true),
                listReady = listReady,
            ) {
                reads += 1
                flowOf(StatsDashboard(totalDuration = 42L))
            }.collect { collected.add(it) }
        }
        runCurrent()
        assertEquals(0, reads)
        assertEquals(emptyList<StatsDashboard?>(), collected)

        listReady.value = true
        runCurrent()
        job.cancel()

        assertEquals(1, reads)
        assertEquals(42L, collected.single()?.totalDuration)
    }

    @Test
    fun `list refreshes do not restart the dashboard read`() = runTest {
        val listReady = MutableStateFlow(true)
        var reads = 0

        val job = launch {
            observeStatsSummary(
                isEnabled = flowOf(true),
                listReady = listReady,
            ) {
                reads += 1
                flowOf(StatsDashboard())
            }.collect { }
        }
        runCurrent()

        // The list re-emits on every history/facet invalidation; the summary must not
        // re-run its whole dashboard walk along with them.
        listReady.value = false
        runCurrent()
        listReady.value = true
        runCurrent()
        job.cancel()

        assertEquals(1, reads)
    }

    @Test
    fun `enabling stats with the list already loaded reads immediately`() = runTest {
        val isEnabled = MutableStateFlow(false)
        var reads = 0

        val job = launch {
            observeStatsSummary(
                isEnabled = isEnabled,
                listReady = flowOf(true),
            ) {
                reads += 1
                flowOf(StatsDashboard())
            }.collect { }
        }
        runCurrent()
        assertEquals(0, reads)

        isEnabled.value = true
        runCurrent()
        job.cancel()

        assertEquals(1, reads)
    }
}

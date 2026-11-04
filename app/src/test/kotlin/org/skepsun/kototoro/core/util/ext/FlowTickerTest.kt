package org.skepsun.kototoro.core.util.ext

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class FlowTickerTest {

    @Test
    fun `completed chapters do not wait for the next progress heartbeat`() = runTest {
        val chapters = List(3) {
            flow {
                delay(10)
                emit(1)
            }.withTicker(2, TimeUnit.SECONDS).toList()
        }

        assertEquals(listOf(listOf(1), listOf(1), listOf(1)), chapters)
        assertEquals(30L, testScheduler.currentTime)
    }

    @Test
    fun `empty transfer completes immediately`() = runTest {
        assertEquals(emptyList<Int>(), emptyFlow<Int>().withTicker(2, TimeUnit.SECONDS).toList())
        assertEquals(0L, testScheduler.currentTime)
    }
}

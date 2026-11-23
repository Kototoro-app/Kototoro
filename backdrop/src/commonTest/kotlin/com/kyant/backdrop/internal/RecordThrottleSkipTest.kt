package com.kyant.backdrop.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RecordThrottleSkipTest {

    @Test
    fun noIntervalRecordsOnEveryDraw() {
        assertTrue(shouldRecordLayer(elapsedSinceLastRecord = 0.milliseconds, recordInterval = 0.milliseconds))
        assertTrue(shouldRecordLayer(elapsedSinceLastRecord = 4.milliseconds, recordInterval = 0.milliseconds))
        // Negative intervals keep the old behaviour too: never silently skip a recording.
        assertTrue(shouldRecordLayer(elapsedSinceLastRecord = 1.milliseconds, recordInterval = (-5).milliseconds))
    }

    @Test
    fun firstDrawAlwaysRecords() {
        assertTrue(shouldRecordLayer(elapsedSinceLastRecord = null, recordInterval = 33.milliseconds))
    }

    @Test
    fun skipsUntilTheIntervalElapsed() {
        val interval = 33.milliseconds
        assertFalse(shouldRecordLayer(elapsedSinceLastRecord = 0.milliseconds, recordInterval = interval))
        assertFalse(shouldRecordLayer(elapsedSinceLastRecord = 16.milliseconds, recordInterval = interval))
        assertTrue(shouldRecordLayer(elapsedSinceLastRecord = 33.milliseconds, recordInterval = interval))
        assertTrue(shouldRecordLayer(elapsedSinceLastRecord = 80.milliseconds, recordInterval = interval))
    }
}

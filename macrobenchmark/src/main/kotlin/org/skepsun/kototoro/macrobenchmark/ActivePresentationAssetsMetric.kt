package org.skepsun.kototoro.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

@OptIn(ExperimentalMetricApi::class)
class ActivePresentationAssetsMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val maxRow = traceSession.query(
            """
            SELECT MAX(counter.value) AS max_val
            FROM counter
            JOIN track ON counter.track_id = track.id
            WHERE track.name = 'Reader.ActivePresentationAssets'
            """.trimIndent(),
        ).firstOrNull()

        val lastRow = traceSession.query(
            """
            SELECT counter.value AS last_val
            FROM counter
            JOIN track ON counter.track_id = track.id
            WHERE track.name = 'Reader.ActivePresentationAssets'
            ORDER BY counter.ts DESC
            LIMIT 1
            """.trimIndent(),
        ).firstOrNull()

        val maxVal = maxRow?.nullableDouble("max_val") ?: 0.0
        val lastVal = lastRow?.nullableDouble("last_val") ?: 0.0

        return listOf(
            Measurement("ActivePresentationAssets_Max", maxVal),
            Measurement("ActivePresentationAssets_Last", lastVal),
        )
    }
}

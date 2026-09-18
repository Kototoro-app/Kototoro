package org.skepsun.kototoro.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Decoded presentation width of the page the reader last promoted.
 *
 * The pipeline constrains its decode to the plan's target (a 6000x9000 page at a 1280-wide
 * viewport resolves to roughly 1500px instead of 6000px), so this counter tells whether a zoomed
 * reader actually asked for more pixels or kept showing an upscaled bitmap.
 */
@OptIn(ExperimentalMetricApi::class)
class PresentationWidthMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val row = traceSession.query(
            """
            SELECT MAX(counter.value) AS max_val, MIN(counter.value) AS min_val
            FROM counter
            JOIN track ON counter.track_id = track.id
            WHERE track.name = 'Reader.PresentationWidthPx'
            """.trimIndent(),
        ).firstOrNull()

        return listOf(
            Measurement("PresentationWidthPx_Max", row?.nullableDouble("max_val") ?: 0.0),
            Measurement("PresentationWidthPx_Min", row?.nullableDouble("min_val") ?: 0.0),
        )
    }
}

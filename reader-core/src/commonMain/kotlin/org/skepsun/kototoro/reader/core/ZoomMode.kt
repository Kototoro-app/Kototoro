package org.skepsun.kototoro.reader.core

/**
 * Baseline fit mode a paged reader lays its pages out with before any user camera zoom.
 *
 * Owned by the reader core (ADR 0002 I1): layout fit is a reading-semantic concept consumed
 * by [PagedSpreadResolver], so the core keeps it instead of referencing an application-layer
 * type. App-side settings and hosts depend on this enum one-way.
 */
enum class ZoomMode {

    FIT_CENTER, FIT_HEIGHT, FIT_WIDTH, KEEP_START
}

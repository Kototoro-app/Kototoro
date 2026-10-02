package org.skepsun.kototoro.scrobbling.common.domain.model

/** SPIKE stand-in: the original also implements the UI `ListModel`; that conformance moves to a wrapper. */
enum class ScrobblingStatus {
    PLANNED, READING, RE_READING, COMPLETED, ON_HOLD, DROPPED;
}

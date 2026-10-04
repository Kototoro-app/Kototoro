package org.skepsun.kototoro.list.domain

/** Completion threshold shared by history filtering and the Android progress indicator. */
fun isReadingCompleted(percent: Float): Boolean = percent >= 0.99999f

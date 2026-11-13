package org.skepsun.kototoro.reader.domain

import java.util.concurrent.atomic.AtomicLong

/** Tracks which prefetch call is the latest so that older, overtaken calls can skip their work. */
internal class PrefetchSequence {

    private val value = AtomicLong(0)

    fun next(): Long = value.incrementAndGet()

    fun isLatest(sequence: Long): Boolean = value.get() == sequence

    /** Makes every call issued so far stale. */
    fun invalidate() {
        value.incrementAndGet()
    }
}

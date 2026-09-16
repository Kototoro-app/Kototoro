package org.skepsun.kototoro.reader.core

/**
 * Type-safe identifier for a reader page within a reading session.
 *
 * Backed by an inlined [Long], directly compatible with Kototoro's existing
 * `ReaderPage.readerKey` hash identity without boxing or allocation overhead.
 */
@JvmInline
value class PageId(val value: Long) {
    override fun toString(): String = "PageId($value)"
}

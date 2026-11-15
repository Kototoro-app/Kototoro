package org.skepsun.kototoro.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The source picker used to render a skeleton and then nothing at all: the source list resolved to
 * zero entries, and no branch of the page said why. The honest answer depends on facts the page
 * already had — whether any source is enabled anywhere, and whether the tab/tag/preset/space/search
 * filters removed them all — so the decision lives here, shared by Android and Windows, instead of
 * being folded into "empty" on each host.
 */
class SourceEmptyHintTest {

    @Test
    fun `sources on screen need no hint`() {
        assertEquals(
            SourceEmptyHintKind.NONE,
            sourceEmptyHintKind(totalSourceCount = 3, visibleSourceCount = 3, isLoading = false),
        )
    }

    @Test
    fun `resolving sources stays a skeleton`() {
        assertEquals(
            SourceEmptyHintKind.NONE,
            sourceEmptyHintKind(totalSourceCount = 0, visibleSourceCount = 0, isLoading = true),
        )
    }

    @Test
    fun `no installed source offers the extension manager, not a filter complaint`() {
        assertEquals(
            SourceEmptyHintKind.NO_SOURCES,
            sourceEmptyHintKind(totalSourceCount = 0, visibleSourceCount = 0, isLoading = false),
        )
    }

    @Test
    fun `filters that hide every source offer to reset them`() {
        assertEquals(
            SourceEmptyHintKind.HIDDEN_BY_FILTERS,
            sourceEmptyHintKind(totalSourceCount = 4, visibleSourceCount = 0, isLoading = false),
        )
    }
}

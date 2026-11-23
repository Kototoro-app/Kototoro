package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef

/** Issue #713: a source page that lists one work twice crashed the browse grid, which keys rows by work id. */
class DesktopBrowseRowsTest {

    private val source = SourceRef("MIHON_1", "en", "MANGA")
    private val other = SourceRef("MIHON_2", "en", "MANGA")

    private fun content(id: Long, ref: SourceRef = source) = SourceContent(id, "work $id", emptySet(), "/work/$id",
        "https://fixture.invalid/work/$id", .5f, null, null, emptySet(), "ONGOING", emptySet(), ref)

    @Test
    fun `a batch keeps each work once in its first position`() {
        val rows = browseRows(emptyList(), listOf(content(1), content(2), content(1), content(3), content(2)))
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
    }

    @Test
    fun `an appended batch drops the works already shown`() {
        val rows = browseRows(listOf(content(1), content(2)), listOf(content(2), content(3), content(3)))
        assertEquals(listOf(3L), rows.map { it.id })
    }

    @Test
    fun `the same id from another source is another work`() {
        val rows = browseRows(listOf(content(1)), listOf(content(1, other)))
        assertEquals(listOf(other), rows.map { it.source })
    }
}

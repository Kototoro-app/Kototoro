package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.library.SourceLibraryEntry
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import java.time.Instant
import java.time.ZoneOffset

class DesktopHistoryGroupsTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val source = SourceRef("MIHON_1", "zh", "MANGA")

    private fun work(id: Long) = SourceContent(id, "Work $id", emptySet(), "/w/$id", "https://fixture.invalid/w/$id",
        -1f, null, null, emptySet(), null, emptySet(), source)

    private fun entry(id: Long, readAt: Instant, percent: Float? = null) = SourceLibraryEntry(
        content = work(id),
        categoryIds = emptySet(), updatedAt = readAt.toEpochMilli(), lastReadAt = readAt.toEpochMilli(),
        progressPercent = percent,
    )

    @Test
    fun `time orders label rows by their date bucket and the title order has none`() {
        val group = requireNotNull(historyGroupOf(DesktopLibraryOrder.LAST_READ, now, zone))
        assertEquals(AndroidStrings["today"], group(entry(1, now.minusSeconds(3_600))))
        assertEquals(AndroidStrings["yesterday"], group(entry(2, now.minusSeconds(86_400))))
        assertEquals(desktopText("history.daysAgo", 3), group(entry(3, now.minusSeconds(3 * 86_400))))
        assertNull(historyGroupOf(DesktopLibraryOrder.TITLE, now, zone))
    }

    @Test
    fun `a header row is inserted only where the label changes`() {
        val contents = (1L..4L).map(::work)
        val labels = mapOf(1L to "A", 2L to "A", 3L to "B", 4L to "B")
        val rows = contentRows(contents) { labels[it.id] }
        assertEquals(listOf("A", "1", "2", "B", "3", "4"), rows.map {
            when (it) { is ContentRow.Header -> it.title; is ContentRow.Work -> contents[it.index].id.toString() }
        })
        assertEquals(contents.size, contentRows(contents, null).size)
    }
}

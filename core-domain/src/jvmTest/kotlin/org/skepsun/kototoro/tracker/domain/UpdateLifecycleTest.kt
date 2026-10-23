package org.skepsun.kototoro.tracker.domain

import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.tracker.data.TrackLogEntity

class UpdateLifecycleTest {
    private val day = 86_400_000L
    private fun chapter(id: Long, discovered: Long, read: Long = 0, branch: String? = null) =
        UpdateChapter("$branch/$id", id, "Chapter $id", branch, day, discovered, read)

    private fun log(id: Long, vararg chapters: UpdateChapter, unseen: Boolean = true) = TrackLogEntity(
        id, 1, chapters.joinToString("\n") { it.title }, chapters.maxOf { it.discoveredAt }, unseen,
        encodeUpdateChapters(chapters.toList()),
    )

    @Test
    fun `Jan 1 publications discovered Jan 3 belong on Jan 3 even after reading`() {
        val discovered = 2 * day + 10
        val event = log(1, chapter(1, discovered, read = 4 * day))
        val group = dailyUpdateGroups(listOf(event), TimeZone.UTC).single()
        assertEquals("1970-01-03", group.day)
        assertEquals(discovered, group.discoveredAt)
        assertEquals(day, group.chapters.single().publishedAt)
        assertEquals(0, group.pendingCount)
        assertFalse(group.isUnseen)
        assertEquals(1, group.count, "completed updates remain in history")
    }

    @Test
    fun `same day batches merge while cross day and branch history stays separate`() {
        val groups = dailyUpdateGroups(listOf(
            log(1, chapter(1, day + 10)), log(2, chapter(2, day + 20)),
            log(3, chapter(3, 2 * day + 10)), log(4, chapter(4, day + 30, branch = "other")),
        ), TimeZone.UTC)
        assertEquals(3, groups.size)
        val sameDay = groups.single { it.day == "1970-01-02" && it.branch == null }
        assertEquals(2, sameDay.count)
        assertEquals(listOf(1L, 2L), sameDay.logs.map { it.id })
        assertEquals(day + 20, sameDay.discoveredAt)
        assertEquals(4, groups.sumOf { it.pendingCount })
    }

    @Test
    fun `seen and partial reading are independent and duplicate device discoveries count once`() {
        val first = chapter(1, day + 10)
        val second = chapter(2, day + 20)
        val groups = dailyUpdateGroups(listOf(
            log(1, first, second, unseen = false),
            log(2, first.copy(discoveredAt = 2 * day, readAt = 3 * day), unseen = false),
        ), TimeZone.UTC)
        val group = groups.single()
        assertEquals(2, group.count)
        assertEquals(1, group.pendingCount)
        assertFalse(group.isUnseen, "seen does not imply read")
        assertEquals(day + 20, group.discoveredAt, "later duplicate detection must not move history")
    }

    @Test
    fun `merge is idempotent commutative and preserves read completion and earliest discovery`() {
        val old = listOf(chapter(1, day))
        val read = listOf(chapter(1, 2 * day, read = 3 * day))
        val result = mergeUpdateChapters(listOf(old, read))
        assertEquals(result, mergeUpdateChapters(listOf(read, old)))
        assertEquals(result, mergeUpdateChapters(listOf(result, old, read)))
        assertEquals(day, result.single().discoveredAt)
        assertEquals(3 * day, result.single().readAt)
        assertEquals(result, decodeUpdateChapters(encodeUpdateChapters(result)))
    }

    @Test
    fun `local day boundaries govern grouping instead of UTC dates`() {
        val groups = dailyUpdateGroups(listOf(log(1, chapter(1, day - 1)), log(2, chapter(2, day + 1))),
            TimeZone.of("Asia/Shanghai"))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().count)
    }

    @Test
    fun `independent duplicate discoveries cannot resurrect seen reminders but genuinely unseen chapters can`() {
        val first = chapter(1, day)
        val seen = log(1, first, unseen = false)
        val duplicate = log(2, first.copy(discoveredAt = 2 * day))
        assertFalse(dailyUpdateGroups(listOf(seen, duplicate), TimeZone.UTC).single().isUnseen)
        val newChapter = log(3, chapter(2, day + 10))
        assertTrue(dailyUpdateGroups(listOf(seen, duplicate, newChapter), TimeZone.UTC).single().isUnseen)
    }

    @Test
    fun `legacy chapter titles do not fabricate reading state`() {
        val group = dailyUpdateGroups(listOf(TrackLogEntity(1, 1, "Chapter 1", day, false)), TimeZone.UTC).single()
        assertEquals(-1, group.pendingCount)
        assertFalse(group.isUnseen)
        assertEquals(1, group.count)
    }
}

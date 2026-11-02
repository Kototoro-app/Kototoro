package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRef

/**
 * Android's `filterCurrentWorkFromRelated` as the details page applies it, plus the `related_manga` switch that decides
 * whether the page asks at all. The rules are the ones Android drops a relisted copy of the open work by: same id, same
 * URL identity, or the same title over the same artwork.
 */
class DesktopRelatedContentTest {

    private val source = SourceRef("fixture", "zh", "MANGA")

    private fun work(
        id: Long,
        title: String = "某作品",
        url: String = "/work/$id",
        publicUrl: String = "https://fixture.invalid/work/$id",
        coverUrl: String? = "https://fixture.invalid/cover/$id.jpg",
        source: SourceRef = this.source,
    ) = SourceContent(id, title, emptySet(), url, publicUrl, 0f, null, coverUrl, emptySet(), null, emptySet(), source)

    @Test
    fun `the work itself and its relisted copies are dropped`() {
        val seed = work(1)
        val candidates = listOf(
            work(1),
            work(2, url = "/work/1", publicUrl = "https://fixture.invalid/work/2"),
            work(3, url = "/elsewhere", publicUrl = "https://fixture.invalid/work/1"),
            work(4, title = "某作品!!", coverUrl = "https://cdn.example.test/a.jpg"),
            work(5, title = "别的作品"),
        )
        assertEquals(listOf(4L, 5L), desktopRelatedWorks(seed, candidates).map(SourceContent::id))
    }

    @Test
    fun `the same title over the same artwork path from another host is the same work`() {
        val seed = work(1, coverUrl = "https://a.example.test/cover/1.jpg")
        val copy = work(2, coverUrl = "https://b.example.test/cover/1.jpg")
        assertEquals(emptyList<Long>(), desktopRelatedWorks(seed, listOf(copy)).map(SourceContent::id))
    }

    @Test
    fun `a different title over shared artwork stays`() {
        val seed = work(1, coverUrl = "https://a.example.test/cover/1.jpg")
        val sequel = work(2, title = "某作品 第二季", coverUrl = "https://a.example.test/cover/1.jpg")
        assertEquals(listOf(2L), desktopRelatedWorks(seed, listOf(sequel)).map(SourceContent::id))
    }

    @Test
    fun `the same work on another source is not the seed`() {
        val seed = work(1)
        val elsewhere = work(1, source = SourceRef("other", "zh", "MANGA"))
        assertEquals(listOf(1L), desktopRelatedWorks(seed, listOf(elsewhere)).map(SourceContent::id))
    }

    @Test
    fun `repeated entries collapse to one rail card`() {
        val seed = work(1)
        val duplicate = work(7, title = "相关作品")
        assertEquals(listOf(7L), desktopRelatedWorks(seed, listOf(duplicate, duplicate)).map(SourceContent::id))
    }

    @Test
    fun `related manga is on by default and round trips through the store`() {
        assertTrue(DesktopServicesSettings().relatedContent)
        assertEquals("related_manga", DesktopServicesSettings.KEY_RELATED_CONTENT)
        val off = DesktopServicesSettings(relatedContent = false).toPreferences()
        assertEquals(SourcePreferenceValue.Toggle(false), off[DesktopServicesSettings.KEY_RELATED_CONTENT])
        val stored = off.mapNotNullValues()
        assertFalse(DesktopServicesSettings.from(stored).relatedContent)
        assertTrue(DesktopServicesSettings.from(emptyMap()).relatedContent)
    }

    private fun Map<String, SourcePreferenceValue?>.mapNotNullValues(): Map<String, SourcePreferenceValue> =
        mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}

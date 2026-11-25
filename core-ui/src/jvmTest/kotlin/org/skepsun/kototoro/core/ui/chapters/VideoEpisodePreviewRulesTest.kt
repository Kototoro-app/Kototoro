package org.skepsun.kototoro.core.ui.chapters

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.DetailsMediaStyle
import org.skepsun.kototoro.core.ui.compose.detailsMediaStylePolicy
import org.skepsun.kototoro.core.ui.compose.resolveMediaFamily

class VideoEpisodePreviewRulesTest {

    @Test
    fun `stripEpisodeLinePrefix removes matching line prefix only`() {
        assertEquals("第1集", stripEpisodeLinePrefix("线路A - 第1集", "线路A"))
        assertEquals("线路B - 第1集", stripEpisodeLinePrefix("线路B - 第1集", "线路A"))
        assertEquals("第1集", stripEpisodeLinePrefix("第1集", null))
    }

    @Test
    fun `hasLongEpisodeTitles checks only the first 50 items`() {
        val shortList = (1..50).map { "第${it}集" }
        assertFalse(hasLongEpisodeTitles(shortList))

        val longAtStart = listOf("第1集 特别加长标题") + shortList
        assertTrue(hasLongEpisodeTitles(longAtStart))

        val longAfter50 = shortList + listOf("第51集 超长标题超过七个字")
        assertFalse(hasLongEpisodeTitles(longAfter50))
    }

    @Test
    fun `selectEpisodePreviewWindow anchors around current episode`() {
        val episodes = (1L..20L).toList()
        assertEquals((1L..8L).toList(), selectEpisodePreviewWindow(episodes, null, { it }, limit = 8))
        assertEquals((4L..11L).toList(), selectEpisodePreviewWindow(episodes, 5L, { it }, limit = 8))
        assertEquals((13L..20L).toList(), selectEpisodePreviewWindow(episodes, 19L, { it }, limit = 8))
        assertEquals((1L..4L).toList(), selectEpisodePreviewWindow((1L..4L).toList(), 3L, { it }, limit = 8))
    }

    @Test
    fun `details media style follows the content type`() {
        assertEquals(DetailsMediaStyle.VIDEO, resolveMediaFamily("VIDEO"))
        assertEquals(DetailsMediaStyle.VIDEO, resolveMediaFamily("HENTAI_VIDEO"))
        assertEquals(DetailsMediaStyle.NOVEL, resolveMediaFamily("NOVEL"))
        assertEquals(DetailsMediaStyle.NOVEL, resolveMediaFamily("HENTAI_NOVEL"))
        assertEquals(DetailsMediaStyle.MANGA, resolveMediaFamily("MANGA"))
        assertEquals(DetailsMediaStyle.MANGA, resolveMediaFamily(null))

        val video = detailsMediaStylePolicy("VIDEO")
        assertEquals(DetailsMediaStyle.VIDEO, video.style)
        assertTrue(video.useVideoHeroBackdrop)
        assertTrue(video.useCenteredHeroHeader)
        assertTrue(video.useInlineEpisodePreview)
        assertFalse(video.showPagesTab)
        assertTrue(video.showBookmarksTab)

        val novel = detailsMediaStylePolicy("HENTAI_NOVEL")
        assertEquals(DetailsMediaStyle.NOVEL, novel.style)
        assertFalse(novel.useVideoHeroBackdrop)
        assertFalse(novel.showPagesTab)
        assertTrue(novel.showBookmarksTab)
        assertEquals(4, novel.synopsisCollapsedMaxLines)

        val manga = detailsMediaStylePolicy("MANGA")
        assertEquals(DetailsMediaStyle.MANGA, manga.style)
        assertTrue(manga.showPagesTab)
        assertTrue(manga.showBookmarksTab)
        assertEquals(3, manga.synopsisCollapsedMaxLines)
    }
}

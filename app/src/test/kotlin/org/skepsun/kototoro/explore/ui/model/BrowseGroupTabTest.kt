package org.skepsun.kototoro.explore.ui.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class BrowseGroupTabTest {

	@Test
	fun `novel tab matches persisted novel content types regardless of source group`() {
		assertTrue(BrowseGroupTab.Novel.matchesContentType(ContentType.NOVEL))
		assertTrue(BrowseGroupTab.Novel.matchesContentType(ContentType.HENTAI_NOVEL))
		assertFalse(BrowseGroupTab.Novel.matchesContentType(ContentType.MANGA))
		assertFalse(BrowseGroupTab.Novel.matchesContentType(ContentType.VIDEO))
	}

	@Test
	fun `video tab matches persisted video content types`() {
		assertTrue(BrowseGroupTab.Video.matchesContentType(ContentType.VIDEO))
		assertTrue(BrowseGroupTab.Video.matchesContentType(ContentType.HENTAI_VIDEO))
		assertFalse(BrowseGroupTab.Video.matchesContentType(ContentType.NOVEL))
		assertFalse(BrowseGroupTab.Video.matchesContentType(ContentType.MANGA))
	}

	@Test
	fun `content tab matches all manga-like content types`() {
		assertTrue(BrowseGroupTab.Content.matchesContentType(ContentType.MANGA))
		assertTrue(BrowseGroupTab.Content.matchesContentType(ContentType.MANHWA))
		assertTrue(BrowseGroupTab.Content.matchesContentType(ContentType.MANHUA))
		assertTrue(BrowseGroupTab.Content.matchesContentType(ContentType.COMICS))
		assertTrue(BrowseGroupTab.Content.matchesContentType(ContentType.ONE_SHOT))
		assertTrue(BrowseGroupTab.Content.matchesContentType(ContentType.HENTAI_MANGA))
		assertFalse(BrowseGroupTab.Content.matchesContentType(ContentType.NOVEL))
		assertFalse(BrowseGroupTab.Content.matchesContentType(ContentType.VIDEO))
	}

	@Test
	fun `all tab matches every content type`() {
		assertTrue(BrowseGroupTab.All.matchesContentType(ContentType.NOVEL))
		assertTrue(BrowseGroupTab.All.matchesContentType(ContentType.VIDEO))
		assertTrue(BrowseGroupTab.All.matchesContentType(ContentType.OTHER))
	}
}

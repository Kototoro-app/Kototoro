package org.skepsun.kototoro.explore.ui.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceTagTest {

	@Test
	fun `ireader tag supports only novel and all content tabs`() {
		assertTrue(SourceTag.IREADER.supportsContentTab(BrowseGroupTab.All))
		assertTrue(SourceTag.IREADER.supportsContentTab(BrowseGroupTab.Novel))
		assertFalse(SourceTag.IREADER.supportsContentTab(BrowseGroupTab.Content))
		assertFalse(SourceTag.IREADER.supportsContentTab(BrowseGroupTab.Video))
	}
}

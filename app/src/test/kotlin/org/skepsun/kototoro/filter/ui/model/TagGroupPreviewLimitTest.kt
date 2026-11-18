package org.skepsun.kototoro.filter.ui.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for issue #683 (follow-up): a source whose category list is small
 * (16 categories on 绅士漫画) used to be folded down to a fixed 8/12 items, leaving a big
 * blank area in the filter sheet. The preview limit now follows the actual group size.
 */
class TagGroupPreviewLimitTest {

	@Test
	fun `small groups are shown in full`() {
		assertEquals(16, tagGroupPreviewLimit(total = 16))
		assertEquals(TAG_GROUP_SHOW_ALL_LIMIT, tagGroupPreviewLimit(total = TAG_GROUP_SHOW_ALL_LIMIT))
		assertFalse(tagGroupNeedsExpand(total = TAG_GROUP_SHOW_ALL_LIMIT))
	}

	@Test
	fun `small groups never need the expand affordance`() {
		assertFalse(tagGroupNeedsExpand(total = 1))
		assertFalse(tagGroupNeedsExpand(total = 16))
		assertFalse(tagGroupNeedsExpand(total = TAG_GROUP_SHOW_ALL_LIMIT))
	}

	@Test
	fun `large groups collapse to the collapsed limit`() {
		val total = TAG_GROUP_SHOW_ALL_LIMIT + 1
		assertEquals(TAG_GROUP_COLLAPSED_LIMIT, tagGroupPreviewLimit(total = total))
		assertTrue(tagGroupNeedsExpand(total = total))
	}

	@Test
	fun `searching renders more tags than the collapsed state`() {
		val total = TAG_GROUP_SEARCH_LIMIT + 10
		assertEquals(TAG_GROUP_SEARCH_LIMIT, tagGroupPreviewLimit(total = total, searching = true))
		assertEquals(TAG_GROUP_COLLAPSED_LIMIT, tagGroupPreviewLimit(total = total))
	}

	@Test
	fun `empty groups stay empty`() {
		assertEquals(0, tagGroupPreviewLimit(total = 0))
		assertFalse(tagGroupNeedsExpand(total = 0))
	}
}

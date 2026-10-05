package org.skepsun.kototoro.explore.ui.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
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

	@Test
	fun `quick filter toggle clears, removes and adds like every Android page did`() {
		val both = setOf(SourceTag.MIHON, SourceTag.ANIYOMI)
		assertEquals(emptySet<SourceTag>(), SourceTag.toggle(both, null))
		assertEquals(setOf(SourceTag.ANIYOMI), SourceTag.toggle(both, SourceTag.MIHON))
		assertEquals(both + SourceTag.BUILTIN, SourceTag.toggle(both, SourceTag.BUILTIN))
	}

	@Test
	fun `menu lists selected tags first and otherwise keeps the entry order`() {
		assertEquals(
			listOf(SourceTag.TSUNDOKU, SourceTag.BUILTIN, SourceTag.MIHON, SourceTag.ANIYOMI),
			SourceTag.menuOrder(listOf(SourceTag.BUILTIN, SourceTag.MIHON, SourceTag.ANIYOMI, SourceTag.TSUNDOKU),
				setOf(SourceTag.TSUNDOKU)),
		)
	}

	@Test
	fun `selected tags combine with OR over the source origin`() {
		val selected = setOf(SourceTag.BUILTIN, SourceTag.TSUNDOKU)
		assertTrue(SourceTag.accepts(emptySet(), null))
		assertTrue(SourceTag.accepts(selected, OriginGroup.NATIVE))
		assertTrue(SourceTag.accepts(selected, OriginGroup.TSUNDOKU))
		assertFalse(SourceTag.accepts(selected, OriginGroup.MIHON))
		assertFalse(SourceTag.accepts(selected, null))
		// matches() keeps its old meaning: the content group never decided a tag.
		assertTrue(SourceTag.MIHON.matches(ContentGroup.NOVEL, OriginGroup.MIHON))
	}
}

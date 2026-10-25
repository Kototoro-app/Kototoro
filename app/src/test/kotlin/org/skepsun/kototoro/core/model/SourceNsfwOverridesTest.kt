package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.extensions.SelectedSourcesNsfwAction
import org.skepsun.kototoro.core.extensions.SourceNsfwOverrideSet
import org.skepsun.kototoro.core.extensions.changedNsfwSources
import org.skepsun.kototoro.core.extensions.resolveSelectedSourcesNsfwAction
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class SourceNsfwOverridesTest {

	@Test
	fun `override forces a non-nsfw source to nsfw`() {
		SourceNsfwOverrides.update(setOf("TEST"), emptySet())

		try {
			assertTrue(TestContentSource.isNsfw())
		} finally {
			SourceNsfwOverrides.update(emptySet(), emptySet())
		}
	}

	@Test
	fun `override forces a nsfw source to sfw`() {
		SourceNsfwOverrides.update(emptySet(), setOf("HENTAI_SOURCE"))
		val source = hentaiSource("HENTAI_SOURCE")

		try {
			assertFalse(source.isNsfw())
		} finally {
			SourceNsfwOverrides.update(emptySet(), emptySet())
		}
	}

	@Test
	fun `sfw override wins over nsfw override`() {
		SourceNsfwOverrides.update(setOf("BOTH"), setOf("BOTH"))

		try {
			assertEquals(false, SourceNsfwOverrides.resolve("BOTH"))
		} finally {
			SourceNsfwOverrides.update(emptySet(), emptySet())
		}
	}

	@Test
	fun `resolve returns null without an override`() {
		SourceNsfwOverrides.update(emptySet(), emptySet())
		assertNull(SourceNsfwOverrides.resolve("ANY_SOURCE"))
	}

	@Test
	fun `withNsfwFlag maps base types to hentai and back`() {
		assertEquals(ContentType.HENTAI_MANGA, ContentType.MANGA.withNsfwFlag(true))
		assertEquals(ContentType.HENTAI_NOVEL, ContentType.NOVEL.withNsfwFlag(true))
		assertEquals(ContentType.HENTAI_VIDEO, ContentType.VIDEO.withNsfwFlag(true))
		assertEquals(ContentType.MANGA, ContentType.HENTAI_MANGA.withNsfwFlag(false))
		assertEquals(ContentType.NOVEL, ContentType.HENTAI_NOVEL.withNsfwFlag(false))
		assertEquals(ContentType.VIDEO, ContentType.HENTAI_VIDEO.withNsfwFlag(false))
		assertEquals(ContentType.MANGA, ContentType.MANGA.withNsfwFlag(false))
		assertEquals(ContentType.COMICS, ContentType.COMICS.withNsfwFlag(true))
		assertEquals(ContentType.OTHER, ContentType.OTHER.withNsfwFlag(true))
	}

	@Test
	fun `selection action resolves uniform and mixed cases`() {
		assertEquals(SelectedSourcesNsfwAction.NONE, resolveSelectedSourcesNsfwAction(0, 0))
		assertEquals(SelectedSourcesNsfwAction.NONE, resolveSelectedSourcesNsfwAction(1, 0))
		assertEquals(SelectedSourcesNsfwAction.SET_NSFW, resolveSelectedSourcesNsfwAction(0, 3))
		assertEquals(SelectedSourcesNsfwAction.SET_SFW, resolveSelectedSourcesNsfwAction(3, 3))
		assertEquals(SelectedSourcesNsfwAction.SET_SFW, resolveSelectedSourcesNsfwAction(4, 3))
		assertEquals(SelectedSourcesNsfwAction.CHOOSE, resolveSelectedSourcesNsfwAction(1, 3))
		assertEquals(SelectedSourcesNsfwAction.CHOOSE, resolveSelectedSourcesNsfwAction(2, 5))
	}

	/**
	 * The stored sets are edited through the rule shared with Windows (`SourceNsfwOverrideSet`) now, so this pins that
	 * the app-side holder still sees exactly what that rule produced.
	 */
	@Test
	fun `shared override set drives the holder the app reads`() {
		val shared = SourceNsfwOverrideSet()
			.withOverride(listOf("TEST"), true)
			.withOverride(listOf("HENTAI_SOURCE"), false)
		SourceNsfwOverrides.update(shared.nsfw, shared.sfw)

		try {
			assertTrue(TestContentSource.isNsfw())
			assertFalse(hentaiSource("HENTAI_SOURCE").isNsfw())
			assertNull(SourceNsfwOverrides.resolve("UNKNOWN_SOURCE"))
		} finally {
			SourceNsfwOverrides.update(emptySet(), emptySet())
		}
	}

	@Test
	fun `only the sources whose flag changes are persisted`() {
		val flags = mapOf("TEST" to false, "HENTAI_SOURCE" to true)

		assertEquals(setOf("TEST"), changedNsfwSources(flags.keys, true) { flags.getValue(it) })
		assertEquals(setOf("HENTAI_SOURCE"), changedNsfwSources(flags.keys, false) { flags.getValue(it) })
	}

	private fun hentaiSource(name: String): ContentSource = object : ContentSource {
		override val name: String = name
		override val locale: String = ""
		override val contentType: ContentType = ContentType.HENTAI_MANGA
	}
}

package org.skepsun.kototoro.local.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

class ContentIndexLocalContentTest {

	@Test
	fun `round trips locally imported content kinds`() {
		listOf(LocalMangaSource, LocalNovelSource, LocalVideoSource).forEach { source ->
			val index = ContentIndex(null)
			index.setContentInfo(content(source))

			val restored = requireNotNull(ContentIndex(index.toString()).getContentInfo())
			assertEquals(source, restored.source)
			assertEquals("Local content", restored.title)
			assertEquals("content://provider/document/item", restored.url)
		}
	}

	@Test
	fun `recovers video imported by versions that stored manga source`() {
		val index = ContentIndex(
			"""
			{
			  "id": 42,
			  "title": "Legacy video",
			  "url": "file:///storage/emulated/0/video/Legacy video",
			  "public_url": "",
			  "source": "${LocalMangaSource.name}",
			  "tags": [],
			  "chapters": {
			    "7": {
			      "name": "Episode 1",
			      "url": "file:///storage/emulated/0/video/Legacy video/episode.mp4",
			      "source": "${LocalMangaSource.name}"
			    }
			  }
			}
			""".trimIndent(),
		)

		val restored = requireNotNull(index.getContentInfo())

		assertEquals(LocalVideoSource, restored.source)
		assertEquals(LocalVideoSource, restored.chapters?.single()?.source)
	}

	@Test
	fun `reads legacy aliases and root archive chapter metadata`() {
		val index = ContentIndex(
			"""
			{
			  "id": 9,
			  "title": "Legacy manga",
			  "title_alt": "Old alternate title",
			  "author": "Old author",
			  "url": "file:///storage/emulated/0/manga/legacy.cbz",
			  "source": "${LocalMangaSource.name}",
			  "nsfw": true,
			  "tags": [],
			  "chapters": {
			    "11": {
			      "name": "Chapter 1",
			      "url": "https://example.org/chapter/1",
			      "order": 1,
			      "file": "",
			      "entries": "legacy_page_\\d+"
			    }
			  }
			}
			""".trimIndent(),
		)

		val restored = requireNotNull(index.getContentInfo())

		assertEquals(setOf("Old alternate title"), restored.altTitles)
		assertEquals(setOf("Old author"), restored.authors)
		assertEquals("", index.getChapterFileName(11))
		assertTrue(index.getChapterNamesPattern(requireNotNull(restored.chapters).single()).matches("legacy_page_12"))
	}

	private fun content(source: ContentSource) = Content(
		id = source.name.hashCode().toLong(),
		title = "Local content",
		altTitles = emptySet(),
		url = "content://provider/document/item",
		publicUrl = "content://provider/document/item",
		rating = -1f,
		contentRating = null,
		coverUrl = "",
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = source,
	)
}

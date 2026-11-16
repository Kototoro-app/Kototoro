package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * Parsers emit site-relative paths such as `/photos-index-page-1-aid-327407.html`,
 * so the `.html` suffix of a plain parser url must never be read as "this is a novel":
 * a declared image source has to keep opening in the image reader.
 */
class ContentTypeHeuristicsTest {

	@Test
	fun `site relative html urls keep a declared image source out of the novel reader`() {
		val content = content(
			source = remoteSource("WNACG", ContentType.HENTAI_MANGA),
			url = "/photos-index-page-1-aid-327407.html",
			chapterUrls = listOf(
				"/photos-slist-aid-327407.html",
				"/photos-slide-aid-327405-sid-327407.html",
			),
		)

		assertFalse(content.looksLikeLocalNovelContent())
	}

	@Test
	fun `declared manga sources are never turned into novels by their chapter urls`() {
		val content = content(
			source = remoteSource("SOME_IMAGE_SITE", ContentType.MANGA),
			url = "/comic/891.html",
			chapterUrls = listOf("/comic/891/1.html", "/comic/891/2.xhtml"),
		)

		assertFalse(content.looksLikeLocalNovelContent())
	}

	@Test
	fun `a source that declares novel is a novel whatever its urls look like`() {
		val content = content(
			source = remoteSource("SOME_NOVEL_SITE", ContentType.NOVEL),
			url = "/book/891",
			chapterUrls = listOf("/book/891/read/1"),
		)

		assertTrue(content.looksLikeLocalNovelContent())
	}

	@Test
	fun `an untyped source still falls back to the url heuristic`() {
		val content = content(
			source = remoteSource("SOME_RULE_SOURCE", ContentType.OTHER),
			url = "/novels/book.epub",
			chapterUrls = listOf("/novels/book.epub#chapter/0"),
		)

		assertTrue(content.looksLikeLocalNovelContent())
	}

	@Test
	fun `a local manga source with text chapters is still detected as a novel`() {
		val content = content(
			source = LocalMangaSource,
			url = "file:///storage/emulated/0/novels/book",
			chapterUrls = listOf("file:///storage/emulated/0/novels/book/ch1.txt"),
		)

		assertTrue(content.looksLikeLocalNovelContent())
	}

	@Test
	fun `a local manga source with image chapters is not a novel`() {
		val content = content(
			source = LocalMangaSource,
			url = "file:///storage/emulated/0/manga/book",
			chapterUrls = listOf("file:///storage/emulated/0/manga/book/0_Ch_1.cbz#00001.jpg"),
		)

		assertFalse(content.looksLikeLocalNovelContent())
	}

	@Test
	fun `site relative video routes keep a declared image source out of the video player`() {
		val content = content(
			source = remoteSource("SOME_GALLERY_SITE", ContentType.HENTAI_MANGA),
			url = "/gallery/327407",
			chapterUrls = listOf("/video/327407.html", "/video/327408.html"),
		)

		assertFalse(content.looksLikeLocalVideoContent())
	}

	@Test
	fun `a real media file beats a declared image type`() {
		// Mixed sources such as SimpCity host videos next to galleries without a video type of their own.
		val content = content(
			source = remoteSource("SOME_GALLERY_SITE", ContentType.HENTAI_MANGA),
			url = "/threads/12345",
			chapterUrls = listOf("https://cdn.example.com/media/clip.mp4"),
		)

		assertTrue(content.looksLikeLocalVideoContent())
	}

	@Test
	fun `a source that declares video is a video whatever its urls look like`() {
		val content = content(
			source = remoteSource("SOME_VIDEO_SITE", ContentType.HENTAI_VIDEO),
			url = "/watch/891",
			chapterUrls = listOf("/gallery/891/1"),
		)

		assertTrue(content.looksLikeLocalVideoContent())
	}

	@Test
	fun `an untyped source still reads video urls as video`() {
		val content = content(
			source = remoteSource("SOME_RULE_SOURCE", ContentType.OTHER),
			url = "/video/891",
			chapterUrls = listOf("/video/891"),
		)

		assertTrue(content.looksLikeLocalVideoContent())
	}

	@Test
	fun `a local manga source with a video chapter is still detected as a video`() {
		val content = content(
			source = LocalMangaSource,
			url = "file:///storage/emulated/0/downloads/some movie",
			chapterUrls = listOf("file:///storage/emulated/0/downloads/some movie/part 1.mkv"),
		)

		assertTrue(content.looksLikeLocalVideoContent())
	}

	private fun remoteSource(name: String, type: ContentType): ContentSource = object : ContentSource {
		override val name: String = name
		override val locale: String = "zh"
		override val contentType: ContentType = type
	}

	private fun content(source: ContentSource, url: String, chapterUrls: List<String>): Content = Content(
		id = 1L,
		title = "Test",
		altTitles = emptySet(),
		url = url,
		publicUrl = "",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		chapters = chapterUrls.mapIndexed { index, chapterUrl ->
			ContentChapter(
				id = index.toLong(),
				title = "Ch ${index + 1}",
				number = (index + 1).toFloat(),
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		},
		source = source,
	)
}

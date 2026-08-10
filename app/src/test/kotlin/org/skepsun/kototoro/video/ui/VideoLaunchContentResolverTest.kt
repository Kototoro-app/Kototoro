package org.skepsun.kototoro.video.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

class VideoLaunchContentResolverTest {

	@Test
	fun `intent chapters win over incomplete stored snapshot`() {
		val stored = content(chapters = null)
		val intent = content(chapters = listOf(chapter(1L)))

		preferCompleteLaunchContent(stored, intent) shouldBe intent
	}

	@Test
	fun `complete stored snapshot remains authoritative`() {
		val stored = content(chapters = listOf(chapter(1L)))
		val intent = content(chapters = listOf(chapter(2L)))

		preferCompleteLaunchContent(stored, intent) shouldBe stored
	}

	private fun content(chapters: List<ContentChapter>?): Content = Content(
		id = 10L,
		title = "Video",
		altTitles = emptySet(),
		url = "cloudstream:content",
		publicUrl = "https://example.org/details",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		chapters = chapters,
		source = TestVideoSource,
	)

	private fun chapter(id: Long) = ContentChapter(
		id = id,
		title = "Episode $id",
		number = id.toFloat(),
		volume = 1,
		url = "cloudstream:$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = TestVideoSource,
	)

	private data object TestVideoSource : ContentSource {
		override val name = "test-video"
		override val locale = ""
		override val contentType = ContentType.VIDEO
	}
}

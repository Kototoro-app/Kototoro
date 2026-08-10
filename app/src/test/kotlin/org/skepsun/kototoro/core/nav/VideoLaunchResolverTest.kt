package org.skepsun.kototoro.core.nav

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.reader.ui.ReaderState

class VideoLaunchResolverTest {

	@Test
	fun `uses resumed chapter locator instead of public page url`() {
		val manga = content(
			chapters = listOf(
				chapter(1L, "cloudstream:first"),
				chapter(2L, "cloudstream:resumed"),
			),
		)

		resolveVideoLaunchTarget(manga, ReaderState(2L, 0, 0)) shouldBe VideoLaunchTarget(
			url = "cloudstream:resumed",
			state = ReaderState(2L, 0, 0),
		)
	}

	@Test
	fun `falls back to first chapter when saved chapter is stale`() {
		val manga = content(chapters = listOf(chapter(7L, "cloudstream:first")))

		resolveVideoLaunchTarget(manga, ReaderState(99L, 0, 0)) shouldBe VideoLaunchTarget(
			url = "cloudstream:first",
			state = ReaderState(7L, 0, 0),
		)
	}

	@Test
	fun `keeps requested state until chapters are loaded`() {
		val state = ReaderState(2L, 0, 0)

		resolveVideoLaunchTarget(content(chapters = null), state) shouldBe VideoLaunchTarget(
			url = "https://example.org/details",
			state = state,
		)
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

	private fun chapter(id: Long, url: String) = ContentChapter(
		id = id,
		title = "Episode $id",
		number = id.toFloat(),
		volume = 1,
		url = url,
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

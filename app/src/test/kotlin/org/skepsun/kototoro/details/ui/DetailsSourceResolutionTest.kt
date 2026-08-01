package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.parsers.model.ContentType

class DetailsSourceResolutionTest {

	@Test
	fun `enabled jar source replaces an anonymous restored source`() {
		val restored = ContentSource("JAR_MANGA")
		val loaded = TestSource("JAR_MANGA", ContentType.MANGA, "zh")

		val result = selectResolvedDetailsSource(
			original = restored,
			enabledSources = listOf(ContentSourceInfo(loaded, isEnabled = true, isPinned = false)),
			pipelineResolved = restored,
		)

		assertSame(loaded, result)
	}

	@Test
	fun `pipeline jar source replaces an anonymous restored source`() {
		val restored = ContentSource("JAR_MANGA")
		val loaded = TestSource("JAR_MANGA", ContentType.MANGA, "zh")

		val result = selectResolvedDetailsSource(
			original = restored,
			enabledSources = emptyList(),
			pipelineResolved = loaded,
		)

		assertSame(loaded, result)
	}

	private data class TestSource(
		override val name: String,
		override val contentType: ContentType,
		override val locale: String,
	) : org.skepsun.kototoro.parsers.model.ContentSource
}

package org.skepsun.kototoro.core.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType

class ParserModelConstructorAbiTest {

	@Test
	fun `legacy Content constructor remains callable`() {
		val constructor = Content::class.java.getConstructor(*legacyContentParameterTypes)

		val content = constructor.newInstance(*legacyContentArguments) as Content

		assertEquals(7L, content.id)
		assertEquals("large", content.largeCoverUrl)
		assertEquals("description", content.description)
		assertEquals(1, content.chapters?.size)
		assertNull(content.sourceData)
	}

	@Test
	fun `legacy Content default mask constructor preserves old bit positions`() {
		val markerClass = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
		val constructor = Content::class.java.getConstructor(
			*legacyContentParameterTypes,
			Int::class.javaPrimitiveType,
			markerClass,
		)

		val content = constructor.newInstance(
			*legacyContentArguments,
			(1 shl 11) or (1 shl 12) or (1 shl 13),
			null,
		) as Content

		assertNull(content.largeCoverUrl)
		assertNull(content.description)
		assertNull(content.chapters)
		assertNull(content.sourceData)
	}

	@Test
	fun `legacy ContentChapter constructor remains callable`() {
		val constructor = ContentChapter::class.java.getConstructor(
			Long::class.javaPrimitiveType,
			String::class.java,
			Float::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			String::class.java,
			String::class.java,
			Long::class.javaPrimitiveType,
			String::class.java,
			ContentSource::class.java,
		)

		val chapter = constructor.newInstance(
			9L,
			"Chapter 9",
			9f,
			1,
			"/chapter-9",
			"team",
			123L,
			"main",
			TestContentSource,
		) as ContentChapter

		assertEquals(9L, chapter.id)
		assertNull(chapter.sourceData)
	}

	@Test
	fun `legacy Content copy default remains callable and preserves source data`() {
		val method = Content::class.java.getMethod(
			"copy\$default",
			Content::class.java,
			*legacyContentParameterTypes,
			Int::class.javaPrimitiveType,
			Any::class.java,
		)
		val original = content(chapters = listOf(chapter()), sourceData = "content-memo")
		val mask = ((1 shl 15) - 1) xor (1 shl 12)
		val arguments = legacyContentArguments.copyOf().also {
			it[1] = null
			it[2] = null
			it[3] = null
			it[4] = null
			it[8] = null
			it[10] = null
			it[12] = "updated description"
			it[14] = null
		}

		val copied = method.invoke(null, original, *arguments, mask, null) as Content

		assertEquals("updated description", copied.description)
		assertEquals(original.id, copied.id)
		assertEquals("content-memo", copied.sourceData)
	}

	@Test
	fun `legacy ContentChapter copy default remains callable and preserves source data`() {
		val method = ContentChapter::class.java.getMethod(
			"copy\$default",
			ContentChapter::class.java,
			Long::class.javaPrimitiveType,
			String::class.java,
			Float::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			String::class.java,
			String::class.java,
			Long::class.javaPrimitiveType,
			String::class.java,
			ContentSource::class.java,
			Int::class.javaPrimitiveType,
			Any::class.java,
		)
		val original = chapter(sourceData = "chapter-memo")
		val mask = ((1 shl 9) - 1) xor (1 shl 1)

		val copied = method.invoke(
			null,
			original,
			0L,
			"Updated title",
			0f,
			0,
			null,
			null,
			0L,
			null,
			null,
			mask,
			null,
		) as ContentChapter

		assertEquals("Updated title", copied.title)
		assertEquals(original.id, copied.id)
		assertEquals("chapter-memo", copied.sourceData)
	}

	@Test
	fun `current constructors retain source data`() {
		val chapter = chapter(sourceData = "chapter-memo")
		val content = content(chapters = listOf(chapter), sourceData = "content-memo")

		assertEquals("content-memo", content.sourceData)
		assertEquals("chapter-memo", content.chapters?.single()?.sourceData)
	}

	private val legacyContentParameterTypes = arrayOf(
		Long::class.javaPrimitiveType,
		String::class.java,
		Set::class.java,
		String::class.java,
		String::class.java,
		Float::class.javaPrimitiveType,
		ContentRating::class.java,
		String::class.java,
		Set::class.java,
		ContentState::class.java,
		Set::class.java,
		String::class.java,
		String::class.java,
		List::class.java,
		ContentSource::class.java,
	)

	private val legacyContentArguments = arrayOf(
		7L,
		"Title",
		emptySet<String>(),
		"/title",
		"https://example.org/title",
		0.8f,
		null,
		null,
		emptySet<ContentTag>(),
		null,
		emptySet<String>(),
		"large",
		"description",
		listOf(chapter()),
		TestContentSource,
	)

	private fun content(
		chapters: List<ContentChapter>?,
		sourceData: String?,
	): Content {
		return Content(
			id = 7L,
			title = "Title",
			altTitles = emptySet(),
			url = "/title",
			publicUrl = "https://example.org/title",
			rating = 0.8f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			chapters = chapters,
			source = TestContentSource,
			sourceData = sourceData,
		)
	}

	private fun chapter(sourceData: String? = null): ContentChapter {
		return ContentChapter(
			id = 9L,
			title = "Chapter 9",
			number = 9f,
			volume = 1,
			url = "/chapter-9",
			scanlator = "team",
			uploadDate = 123L,
			branch = "main",
			source = TestContentSource,
			sourceData = sourceData,
		)
	}

	private data object TestContentSource : ContentSource {
		override val name: String = "TEST"
		override val locale: String = "en"
		override val contentType: ContentType = ContentType.MANGA
	}
}

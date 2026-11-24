package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.details.ui.model.DetailsChapterSourceTab
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
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

	@Test
	fun `lazy selectResolvedDetailsSource skips pipeline when enabled source matches`() {
		val restored = ContentSource("JAR_MANGA")
		val loaded = TestSource("JAR_MANGA", ContentType.MANGA, "zh")
		var pipelineInvocations = 0

		val result = selectResolvedDetailsSource(
			original = restored,
			enabledSource = loaded,
			resolvePipeline = {
				pipelineInvocations++
				restored
			},
		)

		assertSame(loaded, result)
		assertEquals(0, pipelineInvocations)
	}

	@Test
	fun `structural source equality distinguishes anonymous placeholder from resolved source symmetrically`() {
		val anonymous = ContentSource("MANGADEX")
		val resolved = TestSource("MANGADEX", ContentType.MANGA, "en")

		assertFalse(anonymous.isStructurallySameSource(resolved))
		assertFalse(resolved.isStructurallySameSource(anonymous))

		val anonOption = DetailsSourceOption(key = "base:MANGADEX", source = anonymous, isSelected = true)
		val resolvedOption = DetailsSourceOption(key = "base:MANGADEX", source = resolved, isSelected = true)

		assertNotEquals(anonOption, resolvedOption)
		assertNotEquals(resolvedOption, anonOption)
	}

	@Test
	fun `DetailsSourceOption deduplicates equivalent resolved options`() {
		val first = DetailsSourceOption(
			key = "base:MANGADEX",
			source = TestSource("MANGADEX", ContentType.MANGA, "en"),
			title = "Example",
			coverUrl = "https://example.com/cover.jpg",
			isSelected = true,
		)
		val second = DetailsSourceOption(
			key = "base:MANGADEX",
			source = TestSource("MANGADEX", ContentType.MANGA, "en"),
			title = "Example",
			coverUrl = "https://example.com/cover.jpg",
			isSelected = true,
		)

		assertEquals(first, second)
		assertEquals(first.hashCode(), second.hashCode())
	}

	@Test
	fun `structural equality detects json source entity metadata and enabled changes`() {
		val entityV1 = JsonSourceEntity(
			id = "JSON_JS_DEMO",
			name = "Old Name",
			type = JsonSourceType.JS,
			config = "{}",
			enabled = true,
			createdAt = 1L,
			updatedAt = 1L,
		)
		val entityV2 = entityV1.copy(name = "New Name", updatedAt = 2L)
		val sourceV1 = JsonContentSource(entityV1)
		val sourceV2 = JsonContentSource(entityV2)

		assertFalse(sourceV1.isStructurallySameSource(sourceV2))
		assertNotEquals(
			DetailsSourceOption(key = "base:JSON_JS_DEMO", source = sourceV1, isSelected = true),
			DetailsSourceOption(key = "base:JSON_JS_DEMO", source = sourceV2, isSelected = true),
		)
		assertNotEquals(
			DetailsChapterSourceTab(key = "reading:JSON_JS_DEMO", source = sourceV1, isSelected = true),
			DetailsChapterSourceTab(key = "reading:JSON_JS_DEMO", source = sourceV2, isSelected = true),
		)
	}

	@Test
	fun `SourceBindingUiState equality deduplicates identical snapshots and detects real changes`() {
		val source = TestSource("MANGADEX", ContentType.MANGA, "en")
		val option = DetailsSourceOption(key = "base:MANGADEX", source = source, isSelected = true)
		val tab = DetailsChapterSourceTab(key = "reading:MANGADEX", source = source, isSelected = true)

		val stateA = SourceBindingUiState(
			metadataSourceOptions = listOf(option),
			readingSourceOptions = listOf(option.copy(key = "reading:MANGADEX")),
			metadataChapterTabs = emptyList(),
			readingChapterTabs = listOf(tab),
			resolvedMetadataContentType = ContentType.MANGA,
			resolvedMetadataLanguage = "en",
			resolvedReadingLanguage = "en",
		)
		val stateB = SourceBindingUiState(
			metadataSourceOptions = listOf(
				DetailsSourceOption(
					key = "base:MANGADEX",
					source = TestSource("MANGADEX", ContentType.MANGA, "en"),
					isSelected = true,
				),
			),
			readingSourceOptions = listOf(
				DetailsSourceOption(
					key = "reading:MANGADEX",
					source = TestSource("MANGADEX", ContentType.MANGA, "en"),
					isSelected = true,
				),
			),
			metadataChapterTabs = emptyList(),
			readingChapterTabs = listOf(
				DetailsChapterSourceTab(
					key = "reading:MANGADEX",
					source = TestSource("MANGADEX", ContentType.MANGA, "en"),
					isSelected = true,
				),
			),
			resolvedMetadataContentType = ContentType.MANGA,
			resolvedMetadataLanguage = "en",
			resolvedReadingLanguage = "en",
		)
		val stateWithUpdatedLocale = stateB.copy(resolvedReadingLanguage = "ja")

		assertEquals(stateA, stateB)
		assertNotEquals(stateA, stateWithUpdatedLocale)
	}

	@Test
	fun `areSourceInfoListsEquivalent compares wrapped sources structurally`() {
		val anon = listOf(ContentSourceInfo(ContentSource("MANGADEX"), isEnabled = true, isPinned = false))
		val resolved = listOf(
			ContentSourceInfo(TestSource("MANGADEX", ContentType.MANGA, "en"), isEnabled = true, isPinned = false),
		)
		val resolvedCopy = listOf(
			ContentSourceInfo(TestSource("MANGADEX", ContentType.MANGA, "en"), isEnabled = true, isPinned = false),
		)

		assertFalse(areSourceInfoListsEquivalent(anon, resolved))
		assertTrue(areSourceInfoListsEquivalent(resolved, resolvedCopy))
	}

	private data class TestSource(
		override val name: String,
		override val contentType: ContentType,
		override val locale: String,
	) : org.skepsun.kototoro.parsers.model.ContentSource
}

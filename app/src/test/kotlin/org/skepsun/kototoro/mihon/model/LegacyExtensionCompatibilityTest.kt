package org.skepsun.kototoro.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentRating

class LegacyExtensionCompatibilityTest {

	// A mock that mimics a legacy extension compiled only with SManga v1 properties
	class LegacySMangaMock : SManga {
		override lateinit var url: String
		override lateinit var title: String
		override var artist: String? = null
		override var author: String? = null
		override var description: String? = null
		override var genre: String? = null
		override var status: Int = 0
		override var thumbnail_url: String? = null
		override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
		override var initialized: Boolean = false
	}

	// A mock that mimics a legacy extension compiled only with SChapter v1 properties
	class LegacySChapterMock : SChapter {
		override lateinit var url: String
		override lateinit var name: String
		override var chapter_number: Float = -1f
		override var scanlator: String? = null
		override var date_upload: Long = 0
	}

	@Test
	fun `toKotoContent handles legacy SManga models seamlessly`() {
		val catalogueSource = mockk<CatalogueSource>()
		every { catalogueSource.id } returns 101L
		every { catalogueSource.name } returns "Legacy Source"
		every { catalogueSource.lang } returns "en"
		every { catalogueSource.supportsLatest } returns true

		val source = MihonMangaSource(
			catalogueSource = catalogueSource,
			pkgName = "org.example.legacy",
			isNsfw = true,
		)

		val legacyManga = LegacySMangaMock().apply {
			url = "/legacy-url"
			title = "Legacy Title"
			genre = "Sci-Fi, Comedy, Adventure"
			status = SManga.ONGOING
			initialized = true
		}

		val content = legacyManga.toKotoContent(source)

		// Assertions check fallback logic is executed seamlessly:
		assertEquals("Legacy Title", content.title)
		assertEquals(emptySet<String>(), content.altTitles) // Defaults to empty list
		assertEquals(ContentRating.ADULT, content.contentRating) // Overridden by source.isNsfw = true
		
		// Tags must be parsed by splitting the legacy genre string
		val tagTitles = content.tags.map { it.title }
		assertEquals(3, tagTitles.size)
		assertTrue(tagTitles.contains("Sci-Fi"))
		assertTrue(tagTitles.contains("Comedy"))
		assertTrue(tagTitles.contains("Adventure"))
	}

	@Test
	fun `toKotoChapter handles legacy SChapter models seamlessly`() {
		val catalogueSource = mockk<CatalogueSource>()
		every { catalogueSource.id } returns 101L
		every { catalogueSource.name } returns "Legacy Source"
		every { catalogueSource.lang } returns "en"
		every { catalogueSource.supportsLatest } returns true

		val source = MihonMangaSource(
			catalogueSource = catalogueSource,
			pkgName = "org.example.legacy",
			isNsfw = false,
		)

		val legacyChapter = LegacySChapterMock().apply {
			url = "/legacy-chapter-url"
			name = "Legacy Chapter Title"
			chapter_number = 42.5f
			scanlator = "Legacy Scanlator Group"
			date_upload = 9876543210L
		}

		val kotoChapter = legacyChapter.toKotoChapter(source)

		// Assertions check fallback logic is executed seamlessly:
		assertEquals(42.5f, kotoChapter.number) // Falls back to chapter_number
		assertEquals(0, kotoChapter.volume) // Falls back to 0
		assertEquals("Legacy Scanlator Group", kotoChapter.scanlator) // Falls back to scanlator string
		assertEquals("Legacy Scanlator Group", kotoChapter.branch)
	}
}

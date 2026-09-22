package org.skepsun.kototoro.core.parser

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ProjectionIdentityResolverTest {

	private val mangaDao = mockk<MangaDao>()
	private val db = mockk<MangaDatabase> {
		every { getMangaDao() } returns mangaDao
	}
	private val resolver = ProjectionIdentityResolver(db)

	@Test
	fun `resolveStoredProjection reuses existing projection with same source and url`() = runTest {
		coEvery { mangaDao.findBySourceAndUrl("source", "/work") } returns mangaWithTags(
			id = 42L,
			source = "source",
			url = "/work",
			publicUrl = "https://example.org/work",
		)

		val resolved = resolver.resolveStoredProjection(content(id = 7L, url = "/work"))

		assertEquals(42L, resolved.id)
	}

	@Test
	fun `resolveStoredProjection assigns local id when content id is occupied by another projection`() = runTest {
		coEvery { mangaDao.findBySourceAndUrl("source", "/new") } returns null
		coEvery { mangaDao.findBySourceAndPublicUrl("source", "https://example.org/new") } returns null
		coEvery { mangaDao.find(7L) } returns mangaWithTags(
			id = 7L,
			source = "source",
			url = "/old",
			publicUrl = "https://example.org/old",
		)
		coEvery { mangaDao.findMinId() } returns 0L
		coEvery { mangaDao.contains(-1L) } returns false

		val resolved = resolver.resolveStoredProjection(
			content(id = 7L, url = "/new", publicUrl = "https://example.org/new"),
		)

		assertEquals(-1L, resolved.id)
	}

	@Test
	fun `resolveStoredProjection keeps original id when projection key is missing`() = runTest {
		val resolved = resolver.resolveStoredProjection(content(id = 7L, url = "", publicUrl = ""))

		assertEquals(7L, resolved.id)
		coVerify(exactly = 0) { mangaDao.find(7L) }
	}

	@Test
	fun `preserveStoredRemoteIdentity restores the saved parser id for a url-less card`() = runTest {
		coEvery { mangaDao.find(42L) } returns mangaWithTags(
			id = 42L,
			source = "KOMIIC",
			url = "12345",
			publicUrl = "https://komiic.cc/comic/12345",
		)

		val preserved = resolver.preserveStoredRemoteIdentity(
			content(id = 42L, url = "", publicUrl = ""),
		)

		assertEquals("12345", preserved.url)
		assertEquals("https://komiic.cc/comic/12345", preserved.publicUrl)
	}

	@Test
	fun `preserveStoredRemoteIdentity keeps whitespace urls from erasing the saved identity`() = runTest {
		coEvery { mangaDao.find(42L) } returns mangaWithTags(
			id = 42L,
			source = "KOMIIC",
			url = "12345",
			publicUrl = "https://komiic.cc/comic/12345",
		)

		val preserved = resolver.preserveStoredRemoteIdentity(
			content(id = 42L, url = "  ", publicUrl = "\t"),
		)

		assertEquals("12345", preserved.url)
		assertEquals("https://komiic.cc/comic/12345", preserved.publicUrl)
	}

	@Test
	fun `preserveStoredRemoteIdentity leaves a real remote identity untouched`() = runTest {
		val incoming = content(id = 42L, url = "67890", publicUrl = "https://komiic.cc/comic/67890")

		assertEquals(incoming, resolver.preserveStoredRemoteIdentity(incoming))
		coVerify(exactly = 0) { mangaDao.find(any()) }
	}

	@Test
	fun `preserveStoredRemoteIdentity cannot restore an already damaged row`() = runTest {
		coEvery { mangaDao.find(42L) } returns mangaWithTags(
			id = 42L,
			source = "KOMIIC",
			url = "",
			publicUrl = "",
		)

		val damaged = content(id = 42L, url = "", publicUrl = "")

		assertEquals(damaged, resolver.preserveStoredRemoteIdentity(damaged))
	}

	@Test
	fun `preserveStoredRemoteIdentity ignores rows that are not stored yet`() = runTest {
		coEvery { mangaDao.find(42L) } returns null

		val card = content(id = 42L, url = "", publicUrl = "")

		assertEquals(card, resolver.preserveStoredRemoteIdentity(card))
	}

	private fun content(
		id: Long,
		url: String,
		publicUrl: String = "https://example.org$url",
	): Content {
		return Content(
			id = id,
			title = "Work $id",
			altTitles = emptySet(),
			url = url,
			publicUrl = publicUrl,
			rating = -1f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = testSource,
		)
	}

	private fun mangaWithTags(
		id: Long,
		source: String,
		url: String,
		publicUrl: String,
	): MangaWithTags {
		return MangaWithTags(
			manga = MangaEntity(
				id = id,
				title = "Work $id",
				altTitles = null,
				url = url,
				publicUrl = publicUrl,
				rating = -1f,
				isNsfw = false,
				contentRating = null,
				coverUrl = "",
				largeCoverUrl = null,
				state = null,
				authors = null,
				source = source,
			),
			tags = emptyList(),
		)
	}

	private companion object {
		val testSource = object : ContentSource {
			override val name: String = "source"
			override val locale: String = ""
			override val contentType: ContentType = ContentType.MANGA
		}
	}
}

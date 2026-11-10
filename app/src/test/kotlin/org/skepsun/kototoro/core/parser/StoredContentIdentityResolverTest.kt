package org.skepsun.kototoro.core.parser

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.longHashCode
import java.io.File

class StoredContentIdentityResolverTest {

	private val mangaDao = mockk<MangaDao>()
	private val db = mockk<MangaDatabase> {
		every { getMangaDao() } returns mangaDao
	}
	private val resolver = StoredContentIdentityResolver(db)

	@Test
	fun `resolveStoredContent reuses existing content with same source and url`() = runTest {
		coEvery { mangaDao.findBySourceAndUrl("source", "/work") } returns mangaWithTags(
			id = 42L,
			source = "source",
			url = "/work",
			publicUrl = "https://example.org/work",
		)

		val resolved = resolver.resolveStoredContent(content(id = 7L, url = "/work"))

		assertEquals(42L, resolved.id)
	}

	@Test
	fun `resolveStoredContent assigns local id when content id is occupied by another content`() = runTest {
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

		val resolved = resolver.resolveStoredContent(
			content(id = 7L, url = "/new", publicUrl = "https://example.org/new"),
		)

		assertEquals(-1L, resolved.id)
	}

	@Test
	fun `resolveStoredContent keeps original id when content key is missing`() = runTest {
		val resolved = resolver.resolveStoredContent(content(id = 7L, url = "", publicUrl = ""))

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


	@Test
	fun `resolveStoredContent re-anchors local EPUB folder to legacy path-hashed row`() = runTest {
		val folder = File("/storage/emulated/0/Books/Series Name (Book 1) #1")
		val folderUri = folder.toURI().toString()
		val newId = folderUri.longHashCode()
		val legacyId = folder.absolutePath.longHashCode()
		coEvery {
			mangaDao.findLocalFolderRowIdByIds(
				source = LocalNovelSource.name,
				legacyId = legacyId,
				currentId = newId,
			)
		} returns legacyId

		val resolved = resolver.resolveStoredContent(
			Content(
				id = newId,
				title = "Book 1",
				altTitles = emptySet(),
				url = folderUri,
				publicUrl = folderUri,
				rating = -1f,
				contentRating = null,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = LocalNovelSource,
			),
		)

		assertEquals(legacyId, resolved.id)
	}

	@Test
	fun `resolveStoredContent re-anchors contained EPUB path via targeted query without full scan`() = runTest {
		val folder = File("/storage/emulated/0/Books/Vol 1")
		val folderUri = folder.toURI().toString()
		val newId = folderUri.longHashCode()
		val legacyId = folder.absolutePath.longHashCode()
		val pathPrefix = "${folder.absolutePath}${File.separatorChar}"
		val cleanUri = folderUri.trimEnd('/')
		coEvery {
			mangaDao.findLocalFolderRowIdByIds(
				source = LocalNovelSource.name,
				legacyId = legacyId,
				currentId = newId,
			)
		} returns null
		coEvery {
			mangaDao.findLocalFolderRowIdByPath(
				source = LocalNovelSource.name,
				title = "Shared Title",
				url = folderUri,
				publicUrl = folderUri,
				pathPrefix = pathPrefix,
				fileUriPrefix = "file://$pathPrefix",
				singleSlashUriPrefix = "file:$pathPrefix",
				encodedUriPrefix = "$cleanUri/",
				legacyEpubPrefix = "localepub://$pathPrefix",
			)
		} returns 200L

		val resolved = resolver.resolveStoredContent(
			Content(
				id = newId,
				title = "Shared Title",
				altTitles = emptySet(),
				url = folderUri,
				publicUrl = folderUri,
				rating = -1f,
				contentRating = null,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = LocalNovelSource,
			),
		)

		assertEquals(200L, resolved.id)
		coVerify(exactly = 0) { mangaDao.findAllBySource(any()) }
	}

	@Test
	fun `resolveStoredContent skips DB lookups for local manga, SAF novels, single files, and indexed folders`(
		@TempDir tempDir: File,
	) = runTest {
		val localMangaFolder = File("/storage/emulated/0/Manga/Series 1")
		val localManga = Content(
			id = localMangaFolder.toURI().toString().longHashCode(),
			title = "Series 1",
			altTitles = emptySet(),
			url = localMangaFolder.toURI().toString(),
			publicUrl = localMangaFolder.toURI().toString(),
			rating = -1f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = LocalMangaSource,
		)
		val safNovel = localManga.copy(
			id = 999L,
			url = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2FVol1",
			publicUrl = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2FVol1",
			source = LocalNovelSource,
		)
		val singleEpubFile = File("/storage/emulated/0/Books/Standalone Book #1.epub")
		val singleEpub = localManga.copy(
			id = singleEpubFile.absolutePath.longHashCode(),
			url = singleEpubFile.toURI().toString(),
			publicUrl = singleEpubFile.toURI().toString(),
			source = LocalNovelSource,
		)
		val indexedNovelDir = File(tempDir, "Indexed Novel Folder").apply {
			mkdirs()
			File(this, "index.json").writeText("{}")
		}
		val indexedFolderNovel = localManga.copy(
			id = indexedNovelDir.toURI().toString().longHashCode(),
			url = indexedNovelDir.toURI().toString(),
			publicUrl = indexedNovelDir.toURI().toString(),
			source = LocalNovelSource,
		)

		assertEquals(localManga, resolver.resolveStoredContent(localManga))
		assertEquals(safNovel, resolver.resolveStoredContent(safNovel))
		assertEquals(singleEpub, resolver.resolveStoredContent(singleEpub))
		assertEquals(indexedFolderNovel, resolver.resolveStoredContent(indexedFolderNovel))
		coVerify(exactly = 0) { db.getMangaDao() }
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

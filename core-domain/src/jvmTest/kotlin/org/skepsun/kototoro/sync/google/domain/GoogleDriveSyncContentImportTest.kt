package org.skepsun.kototoro.sync.google.domain

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.sync.google.data.model.SyncContent

class GoogleDriveSyncContentImportTest {

	@Test
	fun `content without url or public url is skipped and its rows are not remapped onto a local id`() = runTest {
		val store = FakeStore(entity(id = -5L, title = "Local", url = "/local"))

		val import = importSyncContent(listOf(content(id = -5L, title = "Remote", url = "")), store)

		assertNull(import.localIdOf(-5L))
		assertEquals(listOf("Local"), store.rows.values.map { it.title })
	}

	@Test
	fun `url-less content is not duplicated on every sync`() = runTest {
		val store = FakeStore(entity(id = -5L, title = "Ghost", url = ""))

		importSyncContent(listOf(content(id = -5L, title = "Ghost", url = "")), store)
		importSyncContent(listOf(content(id = -5L, title = "Ghost", url = "")), store)

		assertEquals(1, store.rows.size)
	}

	@Test
	fun `allocated id never overwrites content inserted earlier in the same import`() = runTest {
		// Local minimum is -10, so the first allocated id is -11: exactly the id a remote
		// content (e.g. allocated on another device) is inserted with as-is.
		val store = FakeStore(entity(id = -10L, title = "Local A", url = "/a"))

		val import = importSyncContent(
			listOf(
				content(id = -11L, title = "Remote B", url = "/b"),
				content(id = -10L, title = "Remote C", url = "/c"),
			),
			store,
		)

		val b = import.localIdOf(-11L)!!
		val c = import.localIdOf(-10L)!!
		assertEquals("Remote B", store.rows.getValue(b).title)
		assertEquals("Remote C", store.rows.getValue(c).title)
		assertEquals("Local A", store.rows.getValue(-10L).title)
		assertEquals(3, store.rows.size)
	}

	@Test
	fun `content matching a local row by identity reuses the local id`() = runTest {
		val store = FakeStore(entity(id = 7L, title = "Local", url = "/same"))

		val import = importSyncContent(listOf(content(id = 99L, title = "Remote", url = "/same")), store)

		assertEquals(7L, import.localIdOf(99L))
		assertEquals(1, store.rows.size)
	}

	@Test
	fun `new content keeps its remote id when it is free`() = runTest {
		val store = FakeStore()

		val import = importSyncContent(listOf(content(id = 42L, title = "Remote", url = "/new")), store)

		assertEquals(42L, import.localIdOf(42L))
		assertEquals("Remote", store.rows.getValue(42L).title)
	}

	@Test
	fun `ids outside the content list resolve to themselves`() = runTest {
		val import = importSyncContent(emptyList(), FakeStore())

		assertEquals(3L, import.localIdOf(3L))
	}

	private class FakeStore(vararg initial: MangaEntity) : SyncContentStore {
		val rows = LinkedHashMap<Long, MangaEntity>().apply { initial.forEach { put(it.id, it) } }

		override suspend fun findByIdentity(content: SyncContent): MangaEntity? = rows.values.firstOrNull {
			it.source == content.source && content.url.isNotBlank() && it.url == content.url
		}

		override suspend fun findById(id: Long): MangaEntity? = rows[id]

		override suspend fun contains(id: Long): Boolean = id in rows

		override suspend fun findMinId(): Long? = rows.keys.minOrNull()

		override suspend fun upsert(entity: MangaEntity) {
			rows[entity.id] = entity
		}
	}

	private fun content(id: Long, title: String, url: String) = SyncContent(
		id = id,
		title = title,
		url = url,
		publicUrl = "",
		rating = -1f,
		isNsfw = false,
		coverUrl = "",
		source = SOURCE,
	)

	private fun entity(id: Long, title: String, url: String) = content(id, title, url).toEntity()

	private companion object {
		const val SOURCE = "RAWKUMA"
	}
}

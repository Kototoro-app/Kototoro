package org.skepsun.kototoro.entitygraph.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity

class EntityIdentityResetPlannerTest {

	@Test
	fun `same source location creates one duplicate projection group`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "/a", publicUrl = "https://site/a"),
				2L to manga(id = 2L, source = "SRC", url = "/b", publicUrl = "https://site/a"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(1, groups.size)
		assertEquals(listOf(1L, 2L), groups.single().mangaIds)
	}

	@Test
	fun `duplicate projection grouping is transitive across url and public url`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L, 3L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "/a", publicUrl = "https://site/a"),
				2L to manga(id = 2L, source = "SRC", url = "https://site/a", publicUrl = "https://site/b"),
				3L to manga(id = 3L, source = "SRC", url = "https://site/b", publicUrl = "https://site/c"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(1, groups.size)
		assertEquals(listOf(1L, 2L, 3L), groups.single().mangaIds)
	}

	@Test
	fun `same location on different sources stays separate`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "A", url = "/same"),
				2L to manga(id = 2L, source = "B", url = "/same"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L), listOf(2L)), groups.map { it.mangaIds })
	}

	@Test
	fun `same location on different content types stays separate`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "/same", contentType = "MANGA"),
				2L to manga(id = 2L, source = "SRC", url = "/same", contentType = "VIDEO"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L), listOf(2L)), groups.map { it.mangaIds })
	}

	@Test
	fun `canonical projection prefers newest work state`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "/same"),
				2L to manga(id = 2L, source = "SRC", url = "/same"),
			),
			workHistorySnapshot = listOf(history(anchorMangaId = 2L, updatedAt = 99L)),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(2L, groups.single().canonicalMangaId)
	}

	@Test
	fun `reset projection bindings use source scoped projection keys`() {
		val mangaById = mapOf(
			1L to manga(id = 1L, source = "SRC", url = " /same ", publicUrl = "https://site/public"),
			2L to manga(id = 2L, source = "SRC", url = "", publicUrl = " https://site/public "),
			3L to manga(id = 3L, source = "OTHER", url = "/same", publicUrl = ""),
		)
		val group = ResetProjectionGroup(
			mangaIds = listOf(1L, 2L, 3L),
			canonicalMangaId = 1L,
		)

		val bindings = buildResetProjectionBindingKeys(group, mangaById)

		assertEquals(
			listOf(
				ResetProjectionBindingKey(source = "SRC", externalId = "url:/same"),
				ResetProjectionBindingKey(source = "SRC", externalId = "public_url:https://site/public"),
				ResetProjectionBindingKey(source = "OTHER", externalId = "url:/same"),
			),
			bindings,
		)
	}

	@Test
	fun `single reset projection key gets deterministic sync id`() {
		val mangaById = mapOf(
			1L to manga(id = 1L, source = "SRC", url = "/same"),
			2L to manga(id = 2L, source = "SRC", url = "/same"),
		)
		val group = ResetProjectionGroup(
			mangaIds = listOf(1L, 2L),
			canonicalMangaId = 1L,
		)

		assertEquals(
			computeProjectionSyncId("SRC", "url:/same"),
			resetProjectionSyncId(group, mangaById),
		)
	}

	@Test
	fun `multiple reset projection keys keep non projection sync id`() {
		val mangaById = mapOf(
			1L to manga(id = 1L, source = "SRC", url = "/a"),
			2L to manga(id = 2L, source = "SRC", url = "/b"),
		)
		val group = ResetProjectionGroup(
			mangaIds = listOf(1L, 2L),
			canonicalMangaId = 1L,
		)

		assertEquals(null, resetProjectionSyncId(group, mangaById))
	}

	private fun manga(
		id: Long,
		source: String,
		url: String,
		publicUrl: String = "",
		contentType: String? = null,
	): MangaEntity {
		return MangaEntity(
			id = id,
			title = "Title $id",
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
			contentType = contentType,
		)
	}

	private fun history(
		anchorMangaId: Long,
		updatedAt: Long,
	): WorkHistoryEntity {
		return WorkHistoryEntity(
			entityId = anchorMangaId,
			anchorMangaId = anchorMangaId,
			createdAt = 0L,
			updatedAt = updatedAt,
			chapterId = 0L,
			page = 0,
			scroll = 0f,
			percent = 0f,
			deletedAt = 0L,
			chaptersCount = 0,
			parentChapterId = null,
		)
	}
}

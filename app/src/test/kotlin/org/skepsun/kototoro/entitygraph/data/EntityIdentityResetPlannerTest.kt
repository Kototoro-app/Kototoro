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
	fun `same source path on different mirror domains creates one group`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(
					id = 1L,
					source = "SRC",
					url = "https://mirror-a.example/comic/42?lang=zh",
				),
				2L to manga(
					id = 2L,
					source = "SRC",
					url = "https://mirror-b.example/comic/42?lang=zh",
				),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L, 2L)), groups.map { it.mangaIds })
	}

	@Test
	fun `identical snapshots group rows when mirror paths also changed`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "https://old.example/comic/42"),
				2L to manga(id = 2L, source = "SRC", url = "https://new.example/manga/42"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L, 2L)), groups.map { it.mangaIds })
	}

	@Test
	fun `damaged and resolved Komiic rows group by title and mirrored cover path`() {
		val mangaById = mapOf(
			1L to manga(
				id = 1L,
				source = "KOMIIC",
				url = "",
				title = "平成御宅回忆录",
				coverUrl = "https://public.komiic.com/comics/work-id/cover.jpg",
			),
			2L to manga(
				id = 2L,
				source = "KOMIIC",
				url = "3177",
				publicUrl = "https://komiic.com/comic/3177",
				title = "平成御宅回忆录",
				coverUrl = "https://public.komiic.cc/comics/work-id/cover.jpg",
				largeCoverUrl = "https://public.komiic.cc/comics/work-id/cover.jpg",
			),
		)
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mangaById,
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L, 2L)), groups.map { it.mangaIds })
		assertEquals(2L, groups.single().canonicalMangaId)

		val mergePlan = buildCrossEntityProjectionMergePlans(
			groups = buildEquivalentProjectionGroups(mangaById.keys, mangaById),
			entityIdByMangaId = mapOf(1L to 9442L, 2L to 9459L),
			mangaById = mangaById,
		).single()
		assertEquals(2L, mergePlan.canonicalMangaId)
		assertEquals(9459L, mergePlan.targetEntityId)
		assertEquals(listOf(9442L), mergePlan.sourceEntityIds)
	}

	@Test
	fun `cross entity merge detaches duplicates from owners containing another same source work`() {
		val mangaById = mapOf(
			1L to manga(id = 1L, source = "SRC", url = "", title = "Same", coverUrl = "https://a/covers/1.jpg"),
			2L to manga(id = 2L, source = "SRC", url = "2", title = "Same", coverUrl = "https://b/covers/1.jpg"),
			3L to manga(id = 3L, source = "SRC", url = "3", title = "Different", coverUrl = "https://b/covers/3.jpg"),
		)

		val plans = buildCrossEntityProjectionMergePlans(
			groups = buildEquivalentProjectionGroups(mangaById.keys, mangaById),
			entityIdByMangaId = mapOf(1L to 10L, 2L to 20L, 3L to 20L),
			mangaById = mangaById,
		)

		assertEquals(1, plans.size)
		assertEquals(listOf(1L, 2L), plans.single().group.mangaIds)
		assertEquals(
			listOf(2L),
			findDuplicateProjectionIdsWithSameSourceConflict(
				group = plans.single().group,
				entityIdByMangaId = mapOf(1L to 10L, 2L to 20L, 3L to 20L),
				mangaById = mangaById,
			),
		)
	}

	@Test
	fun `incompatible duplicate owners detach only projections in the duplicate group`() {
		val group = EquivalentProjectionGroup(
			source = "SRC",
			mangaIds = listOf(1L, 2L, 3L),
		)
		val entityIdByMangaId = mapOf(
			1L to 10L,
			2L to 20L,
			3L to 30L,
			4L to 10L,
			5L to 20L,
		)

		assertEquals(
			listOf(1L, 2L),
			findDuplicateProjectionIdsToDetach(group, entityIdByMangaId),
		)
	}

	@Test
	fun `different paths on mirror domains stay separate when snapshots differ`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "https://a.example/comic/1", title = "First"),
				2L to manga(id = 2L, source = "SRC", url = "https://b.example/comic/2", title = "Second"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L), listOf(2L)), groups.map { it.mangaIds })
	}

	@Test
	fun `mirror home pages without a work location stay separate`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "https://a.example/", title = "First"),
				2L to manga(id = 2L, source = "SRC", url = "https://b.example/", title = "Second"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L), listOf(2L)), groups.map { it.mangaIds })
	}

	@Test
	fun `same source keeps preferred work and reports every other identity group as conflicting`() {
		val groups = listOf(
			EquivalentProjectionGroup(source = "SRC", mangaIds = listOf(1L, 2L)),
			EquivalentProjectionGroup(source = "SRC", mangaIds = listOf(3L, 4L)),
			EquivalentProjectionGroup(source = "OTHER", mangaIds = listOf(5L)),
		)

		val conflicts = findConflictingSourceProjectionGroups(
			groups = groups,
			preferredMangaId = 3L,
		)

		assertEquals(listOf(listOf(1L, 2L)), conflicts.map { it.mangaIds })
	}

	@Test
	fun `identical damaged projections with no remote identity rebuild as one group`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "", publicUrl = ""),
				2L to manga(id = 2L, source = "SRC", url = "", publicUrl = ""),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(1, groups.size)
		assertEquals(listOf(1L, 2L), groups.single().mangaIds)
	}

	@Test
	fun `different damaged works from the same source stay separate`() {
		val groups = buildResetProjectionGroups(
			mangaIds = listOf(1L, 2L),
			mangaById = mapOf(
				1L to manga(id = 1L, source = "SRC", url = "", publicUrl = "", title = "First"),
				2L to manga(id = 2L, source = "SRC", url = "", publicUrl = "", title = "Second"),
			),
			workHistorySnapshot = emptyList(),
			workFavouriteSnapshot = emptyList(),
		)

		assertEquals(listOf(listOf(1L), listOf(2L)), groups.map { it.mangaIds })
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
		title: String = "Title",
		coverUrl: String = "",
		largeCoverUrl: String? = null,
	): MangaEntity {
		return MangaEntity(
			id = id,
			title = title,
			altTitles = null,
			url = url,
			publicUrl = publicUrl,
			rating = -1f,
			isNsfw = false,
			contentRating = null,
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
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

package org.skepsun.kototoro.space.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.MAIN_LIST_ROUTE_KEY
import org.skepsun.kototoro.space.domain.SpaceListPreferences
import org.skepsun.kototoro.space.domain.SpaceSectionDisplay

class DefaultSpaceRoutePreferencesRepositoryTest {

	private val dao = FakeSpaceRoutePreferencesDao()
	private val repository = DefaultSpaceRoutePreferencesRepository(dao, Json, TestSpaceCatalogRepository())

	@Test
	fun `same route key is isolated by space id`() = runTest {
		val manga = SpaceListPreferences(
			listMode = "GRID",
			gridSize = 110,
			historySortOrder = "LAST_READ",
			favoritesSortOrder = "UPDATED",
			sourceTags = setOf("manga-source"),
		)
		val novel = SpaceListPreferences(
			listMode = "LIST",
			gridSize = 80,
			historySortOrder = "ALPHABETIC",
			favoritesSortOrder = "NEWEST",
			sourceTags = setOf("novel-source"),
		)

		repository.save(BuiltInSpaces.Manga, MAIN_LIST_ROUTE_KEY, manga)
		repository.save(BuiltInSpaces.Novel, MAIN_LIST_ROUTE_KEY, novel)

		repository.load(BuiltInSpaces.Manga, MAIN_LIST_ROUTE_KEY) shouldBe manga
		repository.load(BuiltInSpaces.Novel, MAIN_LIST_ROUTE_KEY) shouldBe novel
	}

	@Test
	fun `page display options are kept per space`() = runTest {
		val manga = SpaceListPreferences(
			listMode = "GRID",
			gridSize = 100,
			sections = mapOf(
				"history" to SpaceSectionDisplay(listMode = "LIST", gridSize = 90, showExtraInfo = true),
				"favorites" to SpaceSectionDisplay(listMode = "COMPACT_GRID", gridSize = 120),
			),
		)

		repository.save(BuiltInSpaces.Manga, MAIN_LIST_ROUTE_KEY, manga)
		repository.save(BuiltInSpaces.Novel, MAIN_LIST_ROUTE_KEY, SpaceListPreferences(listMode = "LIST", gridSize = 80))

		repository.load(BuiltInSpaces.Manga, MAIN_LIST_ROUTE_KEY) shouldBe manga
		repository.load(BuiltInSpaces.Novel, MAIN_LIST_ROUTE_KEY)?.sections shouldBe emptyMap()
	}

	@Test
	fun `preferences saved before pages had their own options still load`() = runTest {
		dao.upsert(
			SpaceRoutePreferencesEntity(
				spaceId = BuiltInSpaces.Manga.value,
				routeKey = MAIN_LIST_ROUTE_KEY,
				payload = """{"listMode":"GRID","gridSize":110}""",
				schemaVersion = 1,
				updatedAt = 1L,
			),
		)

		repository.load(BuiltInSpaces.Manga, MAIN_LIST_ROUTE_KEY) shouldBe
			SpaceListPreferences(listMode = "GRID", gridSize = 110)
	}

	@Test
	fun `unknown schema is ignored without deleting stored payload`() = runTest {
		val entity = SpaceRoutePreferencesEntity(
			spaceId = BuiltInSpaces.Anime.value,
			routeKey = MAIN_LIST_ROUTE_KEY,
			payload = "{}",
			schemaVersion = 999,
			updatedAt = 1L,
		)
		dao.upsert(entity)

		repository.load(BuiltInSpaces.Anime, MAIN_LIST_ROUTE_KEY) shouldBe null
		dao.find(BuiltInSpaces.Anime.value, MAIN_LIST_ROUTE_KEY) shouldBe entity
	}

	private class FakeSpaceRoutePreferencesDao : SpaceRoutePreferencesDao() {
		private val rows = mutableMapOf<Pair<String, String>, SpaceRoutePreferencesEntity>()

		override suspend fun find(spaceId: String, routeKey: String): SpaceRoutePreferencesEntity? {
			return rows[spaceId to routeKey]
		}

		override suspend fun upsert(entity: SpaceRoutePreferencesEntity) {
			rows[entity.spaceId to entity.routeKey] = entity
		}

		override suspend fun delete(spaceId: String, routeKey: String) {
			rows.remove(spaceId to routeKey)
		}

		override suspend fun deleteForSpace(spaceId: String) {
			rows.keys.removeAll { it.first == spaceId }
		}
	}
}

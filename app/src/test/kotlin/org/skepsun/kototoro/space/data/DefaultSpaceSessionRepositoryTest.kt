package org.skepsun.kototoro.space.data

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SPACE_ROUTE_SCHEMA_VERSION
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

class DefaultSpaceSessionRepositoryTest {

	private val dao = FakeSpaceSessionDao()
	private val codec = SpaceRouteCodec(Json { ignoreUnknownKeys = true })
	private val repository = DefaultSpaceSessionRepository(dao, codec, TestSpaceCatalogRepository())

	@Test
	fun `save and load round trips one space snapshot`() = runTest {
		val snapshot = SpaceSessionSnapshot(
			spaceId = BuiltInSpaces.Novel,
			selectedTopLevel = "history",
			resumeRoute = SpaceRouteSnapshot.WorkDetails(42L, 7L),
			stacks = mapOf(
				"history" to listOf(
					SpaceRouteSnapshot.TopLevel("history"),
					SpaceRouteSnapshot.WorkDetails(42L, 7L),
				),
			),
			lastAccessed = 100L,
			updatedAt = 200L,
		)

		repository.save(snapshot)

		repository.load(BuiltInSpaces.Novel) shouldBe snapshot
		dao.sessions.getValue(BuiltInSpaces.Novel.value).resumeEntityId shouldBe 42L
		dao.sessions.getValue(BuiltInSpaces.Novel.value).resumeProjectionId shouldBe 7L
	}

	@Test
	fun `saving again atomically replaces old navigation entries`() = runTest {
		repository.save(snapshotWithRoutes("home", "details"))
		repository.save(snapshotWithRoutes("history"))

		val loaded = requireNotNull(repository.load(BuiltInSpaces.Manga))

		loaded.stacks shouldContainExactly mapOf(
			"main" to listOf(SpaceRouteSnapshot.TopLevel("history")),
		)
		dao.entries.getValue(BuiltInSpaces.Manga.value).map { it.position } shouldContainExactly listOf(0)
	}

	@Test
	fun `invalid entry truncates the remaining stack`() = runTest {
		repository.save(snapshotWithRoutes("home", "history", "favorites"))
		val stored = dao.entries.getValue(BuiltInSpaces.Manga.value).toMutableList()
		stored[1] = stored[1].copy(routeSchemaVersion = SPACE_ROUTE_SCHEMA_VERSION + 1)
		dao.entries[BuiltInSpaces.Manga.value] = stored

		val loaded = requireNotNull(repository.load(BuiltInSpaces.Manga))

		loaded.stacks shouldContainExactly mapOf(
			"main" to listOf(SpaceRouteSnapshot.TopLevel("home")),
		)
	}

	private fun snapshotWithRoutes(vararg keys: String) = SpaceSessionSnapshot(
		spaceId = BuiltInSpaces.Manga,
		selectedTopLevel = keys.first(),
		resumeRoute = null,
		stacks = mapOf("main" to keys.map { SpaceRouteSnapshot.TopLevel(it) }),
		lastAccessed = 100L,
		updatedAt = 200L,
	)
}

private class FakeSpaceSessionDao : SpaceSessionDao() {
	val sessions = LinkedHashMap<String, SpaceSessionEntity>()
	val entries = LinkedHashMap<String, List<SpaceNavigationEntryEntity>>()

	override suspend fun findSession(spaceId: String): SpaceSessionEntity? = sessions[spaceId]

	override suspend fun findNavigationEntries(spaceId: String): List<SpaceNavigationEntryEntity> =
		entries[spaceId].orEmpty()

	override suspend fun upsertSession(entity: SpaceSessionEntity) {
		sessions[entity.spaceId] = entity
	}

	override suspend fun insertNavigationEntries(entries: List<SpaceNavigationEntryEntity>) {
		this.entries[entries.first().spaceId] = entries
	}

	override suspend fun deleteNavigationEntries(spaceId: String) {
		entries.remove(spaceId)
	}

	override suspend fun deleteSession(spaceId: String) {
		sessions.remove(spaceId)
	}
}

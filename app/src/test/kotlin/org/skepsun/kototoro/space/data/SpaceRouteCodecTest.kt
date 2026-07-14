package org.skepsun.kototoro.space.data

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK
import org.skepsun.kototoro.space.domain.SPACE_ROUTE_SCHEMA_VERSION
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot

class SpaceRouteCodecTest {

	private val codec = SpaceRouteCodec(Json { ignoreUnknownKeys = true })

	@Test
	fun `supported routes round trip through explicit payloads`() {
		val routes = listOf(
			SpaceRouteSnapshot.TopLevel("history"),
			SpaceRouteSnapshot.WorkDetails(entityId = 42L, requestedProjectionId = 7L),
			SpaceRouteSnapshot.ContentList(sourceName = "TEST_SOURCE"),
		)

		routes.forEach { route ->
			val encoded = codec.encode(route)
			codec.decode(encoded.kind, encoded.payload, SPACE_ROUTE_SCHEMA_VERSION) shouldBe route
		}
	}

	@Test
	fun `unknown schema and malformed payload are rejected`() {
		val encoded = codec.encode(SpaceRouteSnapshot.TopLevel("home"))

		codec.decode(encoded.kind, encoded.payload, SPACE_ROUTE_SCHEMA_VERSION + 1) shouldBe null
		codec.decode(encoded.kind, "{broken", SPACE_ROUTE_SCHEMA_VERSION) shouldBe null
		codec.decode("UNKNOWN", encoded.payload, SPACE_ROUTE_SCHEMA_VERSION) shouldBe null
	}

	@Test
	fun `storage limit retains root and most recent routes`() {
		val routes = (0..25).map { SpaceRouteSnapshot.TopLevel("route-$it") }

		val limited = routes.limitForStorage()

		limited.size shouldBe MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK
		limited.first() shouldBe routes.first()
		limited.drop(1) shouldBe routes.takeLast(MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK - 1)
	}
}

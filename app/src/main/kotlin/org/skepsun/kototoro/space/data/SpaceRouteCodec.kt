package org.skepsun.kototoro.space.data

import dagger.Reusable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.space.domain.SPACE_ROUTE_SCHEMA_VERSION
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import javax.inject.Inject

internal data class EncodedSpaceRoute(
	val kind: String,
	val payload: String,
)

@Reusable
class SpaceRouteCodec @Inject constructor(
	private val json: Json,
) {

	internal fun encode(route: SpaceRouteSnapshot): EncodedSpaceRoute = when (route) {
		is SpaceRouteSnapshot.TopLevel -> EncodedSpaceRoute(
			kind = KIND_TOP_LEVEL,
			payload = json.encodeToString(SpaceRouteSnapshot.TopLevel.serializer(), route),
		)
		is SpaceRouteSnapshot.WorkDetails -> EncodedSpaceRoute(
			kind = KIND_WORK_DETAILS,
			payload = json.encodeToString(SpaceRouteSnapshot.WorkDetails.serializer(), route),
		)
		is SpaceRouteSnapshot.ContentList -> EncodedSpaceRoute(
			kind = KIND_CONTENT_LIST,
			payload = json.encodeToString(SpaceRouteSnapshot.ContentList.serializer(), route),
		)
	}

	internal fun decode(
		kind: String,
		payload: String?,
		schemaVersion: Int,
	): SpaceRouteSnapshot? {
		if (schemaVersion != SPACE_ROUTE_SCHEMA_VERSION || payload == null) return null
		return runCatching {
			when (kind) {
				KIND_TOP_LEVEL -> json.decodeFromString(SpaceRouteSnapshot.TopLevel.serializer(), payload)
				KIND_WORK_DETAILS -> json.decodeFromString(SpaceRouteSnapshot.WorkDetails.serializer(), payload)
				KIND_CONTENT_LIST -> json.decodeFromString(SpaceRouteSnapshot.ContentList.serializer(), payload)
				else -> null
			}
		}.getOrNull()
	}

	companion object {
		const val KIND_NONE = "NONE"
		const val KIND_TOP_LEVEL = "TOP_LEVEL"
		const val KIND_WORK_DETAILS = "WORK_DETAILS"
		const val KIND_CONTENT_LIST = "CONTENT_LIST"
	}
}

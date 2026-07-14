package org.skepsun.kototoro.space.domain

interface SpaceSessionValidator {

	suspend fun validate(snapshot: SpaceSessionSnapshot): SpaceSessionSnapshot
}

interface SpaceSourceAvailability {

	suspend fun isAvailable(sourceName: String): Boolean
}

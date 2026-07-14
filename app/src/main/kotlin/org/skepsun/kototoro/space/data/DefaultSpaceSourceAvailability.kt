package org.skepsun.kototoro.space.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.space.domain.SpaceSourceAvailability
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceSourceAvailability @Inject constructor(
	private val contentSourcesRepository: ContentSourcesRepository,
) : SpaceSourceAvailability {

	override suspend fun isAvailable(sourceName: String): Boolean = withContext(Dispatchers.IO) {
		contentSourcesRepository.isSourceAvailable(sourceName)
	}
}

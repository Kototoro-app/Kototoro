package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
    private val repository: TrackingRepository,
) {

    suspend operator fun invoke(limit: Int): List<ContentTracking> {
        repository.updateTracks()
        return repository.getTracks(offset = 0, limit = limit)
    }
}

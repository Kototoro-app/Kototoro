package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
    private val repository: TrackingRepository,
) {

    suspend operator fun invoke(
        limit: Int,
        cooldowns: Map<String, Long>,
        challenged: Set<String>,
        respectSchedule: Boolean,
    ): List<ContentTracking> {
        repository.updateTracks()
        return repository.getTracks(limit, cooldowns, challenged, respectSchedule)
    }
}

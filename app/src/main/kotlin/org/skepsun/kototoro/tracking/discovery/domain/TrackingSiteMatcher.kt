package org.skepsun.kototoro.tracking.discovery.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

interface TrackingSiteMatcher {

    suspend fun matchLocalContent(
        service: ScrobblerService,
        content: Content,
        limit: Int = 5,
        persistAutoMatch: Boolean = true,
    ): List<TrackingSiteMatchResult>

    suspend fun confirmMatch(
        service: ScrobblerService,
        contentId: Long,
        remoteId: Long,
    ): TrackingSiteMatchResult

    suspend fun removeMatch(
        service: ScrobblerService,
        contentId: Long,
    )
}

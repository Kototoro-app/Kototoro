package org.skepsun.kototoro.tracking.discovery.domain

import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.EntityType

interface TrackingSiteDiscoveryService {

    fun getCapabilities(service: ScrobblerService): TrackingSiteCapabilities

    suspend fun getTrending(catalog: TrackingSiteCatalog): List<TrackingSiteItem>

    suspend fun search(catalog: TrackingSiteCatalog): List<TrackingSiteItem>

    suspend fun searchEntities(
        service: ScrobblerService,
        entityType: EntityType,
        query: String,
        page: Int = 0,
    ): List<TrackingEntitySearchResult>

    suspend fun getDetails(service: ScrobblerService, remoteId: Long, urlHint: String? = null): TrackingSiteItemDetails

    /** The title drawn as artwork (a logo) in [language] or a fallback, null when the site has none. */
    suspend fun getTitleLogoUrl(service: ScrobblerService, remoteId: Long, language: String): String? = null

    suspend fun getEntityDetails(
        service: ScrobblerService,
        entityType: EntityType,
        remoteId: Long,
        urlHint: String? = null,
    ): TrackingSiteItemDetails?
}

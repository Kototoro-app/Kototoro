package org.skepsun.kototoro.tracking.discovery.domain

import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.entitygraph.domain.EntityType

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

	suspend fun getEntityDetails(
		service: ScrobblerService,
		entityType: EntityType,
		remoteId: Long,
		urlHint: String? = null,
	): TrackingSiteItemDetails?
}

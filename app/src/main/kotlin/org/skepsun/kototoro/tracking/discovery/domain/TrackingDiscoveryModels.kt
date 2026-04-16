package org.skepsun.kototoro.tracking.discovery.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

/**
 * Discovery 层只负责站点浏览、搜索、详情和匹配候选。
 *
 * `TrackingSiteState` / `TrackingSiteLink` 属于后续的同步与持久化层，
 * 不在当前 discovery package 中定义，避免把不同职责的模型混在一起。
 */
data class TrackingSiteCatalog(
	val service: ScrobblerService,
	val query: String? = null,
	val contentType: ContentType? = null,
	val category: String? = null,
	val page: Int = 0,
	val sortOrder: org.skepsun.kototoro.parsers.model.SortOrder? = null,
	val listFilter: org.skepsun.kototoro.parsers.model.ContentListFilter? = null,
)

data class TrackingSiteItem(
	val service: ScrobblerService,
	val remoteId: Long,
	val title: String,
	val altTitle: String? = null,
	val coverUrl: String? = null,
	val subtitle: String? = null,
	val score: Float? = null,
	val url: String? = null,
)

@Deprecated(
	message = "Use TrackingSiteItem",
	replaceWith = ReplaceWith("TrackingSiteItem"),
)
typealias TrackingSiteListItem = TrackingSiteItem

data class TrackingSiteItemDetails(
	val service: ScrobblerService,
	val remoteId: Long,
	val title: String,
	val altTitle: String? = null,
	val altTitles: List<String> = emptyList(),
	val coverUrl: String? = null,
	val description: String? = null,
	val score: Float? = null,
	val rank: Int? = null,
	val tags: List<String> = emptyList(),
	val authors: List<String> = emptyList(),
	val externalLinks: Map<String, String> = emptyMap(),
	val year: Int? = null,
	val totalEpisodes: Int? = null,
	val url: String? = null,
	val infoboxProperties: List<Pair<String, String>> = emptyList(),
	val episodes: List<EpisodeInfo> = emptyList(),
	val relatedWorks: List<RelatedWork> = emptyList(),
	val recommendations: List<RelatedWork> = emptyList(),
) {
	data class EpisodeInfo(
		val number: String,
		val title: String,
		val url: String,
	)

	data class RelatedWork(
		val id: Long,
		val title: String,
		val coverUrl: String,
		val relationship: String? = null,
		val url: String,
	)
}

@Deprecated(
	message = "Use TrackingSiteItemDetails",
	replaceWith = ReplaceWith("TrackingSiteItemDetails"),
)
typealias TrackingSiteDetails = TrackingSiteItemDetails

data class TrackingSiteCategory(
	val id: String,
	val nameResId: Int,
)

data class TrackingSiteCapabilities(
	val supportsDiscovery: Boolean,
	val supportsTrending: Boolean,
	val supportsSearch: Boolean,
	val supportsDetails: Boolean,
	val supportsStatusSync: Boolean,
	val supportsManualBinding: Boolean,
	val discoveryCategories: List<TrackingSiteCategory> = emptyList(),
)

data class TrackingSiteMatchResult(
	val service: ScrobblerService,
	val remoteId: Long,
	val localContent: Content? = null,
	val confidence: Float,
	val title: String,
	val url: String? = null,
	val reason: String? = null,
	val isLinked: Boolean = false,
	val isManual: Boolean = false,
)

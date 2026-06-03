package org.skepsun.kototoro.tracking.discovery.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
	val trackingSortKey: String? = null,
	val calendarDateMillis: Long? = null,
)

private val trackingDateDrivenCategoryIds = setOf(
	"calendar",
	"seasonal",
	"shiki_seasonal",
	"al_anime_airing",
	"simkl_anime_airing",
	"simkl_tv_airing",
)

fun isTrackingDateDrivenCategory(categoryId: String?): Boolean {
	val normalized = categoryId?.substringBefore('_').takeIf { categoryId?.startsWith("calendar_") == true } ?: categoryId
	return normalized in trackingDateDrivenCategoryIds
}

fun trackingCalendarDate(millis: Long?, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? {
	return millis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
}

data class TrackingSeason(
	val year: Int,
	val malSeason: String,
) {
	val shikimoriSeason: String
		get() = "${malSeason}_${year}"
}

fun resolveTrackingSeason(date: LocalDate): TrackingSeason {
	val season = when (date.monthValue) {
		in 1..3 -> "winter"
		in 4..6 -> "spring"
		in 7..9 -> "summer"
		else -> "fall"
	}
	return TrackingSeason(
		year = date.year,
		malSeason = season,
	)
}

data class TrackingSiteItem(
	val service: ScrobblerService,
	val remoteId: Long,
	val title: String,
	val altTitle: String? = null,
	val primaryTitle: String? = null,
	val secondaryTitle: String? = null,
	val progressText: String? = null,
	val updatedAtText: String? = null,
	val coverUrl: String? = null,
	val subtitle: String? = null,
	val score: Float? = null,
	val scoreMax: Float? = null,
	val url: String? = null,
	val totalEpisodes: Int? = null,
)

data class TrackingEntitySearchResult(
	val service: ScrobblerService,
	val entityType: org.skepsun.kototoro.entitygraph.domain.EntityType,
	val remoteId: Long,
	val name: String,
	val altName: String? = null,
	val coverUrl: String? = null,
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
	val coverUrl: String? = null,
	val contentType: ContentType? = null,
	val description: String? = null,
	val score: Float? = null,
	val rank: Int? = null,
	val tags: List<String> = emptyList(),
	val authors: List<String> = emptyList(),
	val staff: List<PersonInfo> = emptyList(),
	val year: Int? = null,
	val totalEpisodes: Int? = null,
	val url: String? = null,
	val infoboxProperties: List<Pair<String, String>> = emptyList(),
	val episodes: List<EpisodeInfo> = emptyList(),
	val characters: List<CharacterInfo> = emptyList(),
	val commentThreads: List<CommentThread> = emptyList(),
	val reviews: List<ReviewEntry> = emptyList(),
	val relatedWorks: List<RelatedWork> = emptyList(),
	val recommendations: List<RelatedWork> = emptyList(),
	val extraSections: List<RelatedSection> = emptyList(),
	val actions: List<ExternalAction> = emptyList(),
) {
	data class EpisodeInfo(
		val number: String,
		val title: String,
		val url: String,
	)

	data class CharacterInfo(
		val id: Long,
		val name: String,
		val coverUrl: String,
		val role: String? = null,
		val url: String,
		val voiceActors: List<PersonInfo> = emptyList(),
	)

	data class PersonInfo(
		val id: Long? = null,
		val name: String,
		val avatarUrl: String? = null,
		val url: String? = null,
		val role: String? = null,
	)

	data class CommentThread(
		val id: String,
		val userName: String,
		val userUrl: String? = null,
		val avatarUrl: String? = null,
		val rating: Float? = null,
		val status: String? = null,
		val postedAt: String? = null,
		val content: String,
		val replies: List<CommentReply> = emptyList(),
	)

	data class CommentReply(
		val id: String,
		val userName: String,
		val userUrl: String? = null,
		val avatarUrl: String? = null,
		val postedAt: String? = null,
		val content: String,
	)

	data class ReviewEntry(
		val id: String,
		val title: String,
		val authorName: String,
		val authorUrl: String? = null,
		val avatarUrl: String? = null,
		val postedAt: String? = null,
		val excerpt: String,
		val url: String,
		val repliesCount: Int? = null,
	)

	data class RelatedWork(
		val id: Long,
		val title: String,
		val coverUrl: String,
		val relationship: String? = null,
		val url: String,
	)

	data class RelatedSection(
		val title: String,
		val items: List<RelatedWork>,
	)

	data class ExternalAction(
		val title: String,
		val url: String,
	)
}

@Deprecated(
	message = "Use TrackingSiteItemDetails",
	replaceWith = ReplaceWith("TrackingSiteItemDetails"),
)
typealias TrackingSiteDetails = TrackingSiteItemDetails

data class TrackingSiteSortOption(
	val id: String,
	val nameResId: Int,
	val targetCategoryId: String? = null,
	val trackingSortKey: String? = null,
)

data class TrackingSiteCategory(
	val id: String,
	val nameResId: Int,
	val sortOptions: List<TrackingSiteSortOption> = emptyList(),
	val defaultSortOptionId: String? = null,
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
	val contentType: ContentType? = null,
	val confidence: Float,
	val title: String,
	val url: String? = null,
	val reason: String? = null,
	val isLinked: Boolean = false,
	val isManual: Boolean = false,
)

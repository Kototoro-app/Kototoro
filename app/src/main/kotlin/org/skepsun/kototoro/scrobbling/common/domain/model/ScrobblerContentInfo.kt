package org.skepsun.kototoro.scrobbling.common.domain.model

class ScrobblerContentInfo(
	val id: Long,
	val name: String,
	val altTitles: List<String> = emptyList(),
	val cover: String,
	val url: String,
	val descriptionHtml: String,
	val tags: List<String> = emptyList(),
	val authors: List<String> = emptyList(),
	val externalLinks: Map<String, String> = emptyMap(),
	val infoboxProperties: List<Pair<String, String>> = emptyList(),
	val episodes: List<EpisodeInfo> = emptyList(),
	val relatedWorks: List<RelatedWork> = emptyList(),
	val recommendations: List<RelatedWork> = emptyList(),
) {
	data class EpisodeInfo(
		val number: String,
		val title: String,
		val url: String,
		val thumbnailUrl: String? = null,
	)

	data class RelatedWork(
		val id: Long,
		val title: String,
		val coverUrl: String,
		val relationship: String? = null,
		val url: String,
	)
}

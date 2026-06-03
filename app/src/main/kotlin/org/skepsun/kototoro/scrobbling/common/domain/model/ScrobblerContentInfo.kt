package org.skepsun.kototoro.scrobbling.common.domain.model

import org.skepsun.kototoro.parsers.model.ContentType

class ScrobblerContentInfo(
	val id: Long,
	val name: String,
	val cover: String,
	val url: String,
	val descriptionHtml: String,
	val contentType: ContentType? = null,
	val score: Float? = null,
	val rank: Int? = null,
	val tags: List<String> = emptyList(),
	val authors: List<String> = emptyList(),
	val staff: List<PersonInfo> = emptyList(),
	val totalEpisodes: Int? = null,
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
		val thumbnailUrl: String? = null,
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

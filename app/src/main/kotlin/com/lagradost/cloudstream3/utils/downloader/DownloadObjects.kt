package com.lagradost.cloudstream3.utils.downloader

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType

/** Records of Cloudstream's downloads and continue-watching row, which plugins read; Kototoro keeps none of them. */
object DownloadObjects {
	data class ResumeWatching(
		@JsonProperty("parentId") val parentId: Int,
		@JsonProperty("episodeId") val episodeId: Int?,
		@JsonProperty("episode") val episode: Int?,
		@JsonProperty("season") val season: Int?,
		@JsonProperty("updateTime") val updateTime: Long,
		@JsonProperty("isFromDownload") val isFromDownload: Boolean,
	)

	data class DownloadHeaderCached(
		@JsonProperty("apiName") val apiName: String,
		@JsonProperty("url") val url: String,
		@JsonProperty("type") val type: TvType,
		@JsonProperty("name") val name: String,
		@JsonProperty("poster") val poster: String?,
		@JsonProperty("id") val id: Int,
		@JsonProperty("cacheTime") val cacheTime: Long,
	)
}

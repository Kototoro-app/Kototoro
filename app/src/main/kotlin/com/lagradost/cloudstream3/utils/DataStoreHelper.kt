package com.lagradost.cloudstream3.utils

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

/**
 * The part of Cloudstream's per-account data store helper plugins use. Kototoro keeps its own library and none of
 * Cloudstream's, so plugins see what a fresh Cloudstream install holds: the single default account (number 0), no
 * bookmarks, no resume points. Sync and statistics plugins (SyncStream, Wrapped) read these; writes are dropped.
 *
 * Constructor parameter order follows Cloudstream: plugins call the data classes' generated `copy$default`.
 */
object DataStoreHelper {

	data class Account(
		@JsonProperty("keyIndex") val keyIndex: Int,
		@JsonProperty("name") val name: String,
		@JsonProperty("customImage") val customImage: String? = null,
		@JsonProperty("defaultImageIndex") val defaultImageIndex: Int,
		@JsonProperty("lockPin") val lockPin: String? = null,
	)

	data class PosDur(
		@JsonProperty("position") val position: Long,
		@JsonProperty("duration") val duration: Long,
	)

	/** A bookmark, favourite or subscription. */
	abstract class LibrarySearchResponse : SearchResponse {
		abstract val latestUpdatedTime: Long
		abstract val year: Int?
		abstract val syncData: Map<String, String>?
		abstract val plot: String?
		abstract val tags: List<String>?
	}

	data class BookmarkedData(
		@JsonProperty("bookmarkedTime") val bookmarkedTime: Long,
		@JsonProperty("id") override var id: Int?,
		@JsonProperty("latestUpdatedTime") override val latestUpdatedTime: Long,
		@JsonProperty("name") override val name: String,
		@JsonProperty("url") override val url: String,
		@JsonProperty("apiName") override val apiName: String,
		@JsonProperty("type") override var type: TvType? = null,
		@JsonProperty("posterUrl") override var posterUrl: String?,
		@JsonProperty("year") override val year: Int?,
		@JsonProperty("syncData") override val syncData: Map<String, String>? = null,
		@JsonProperty("quality") override var quality: SearchQuality? = null,
		@JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
		@JsonProperty("plot") override val plot: String? = null,
		@JsonProperty("score") override var score: Score? = null,
		@JsonProperty("tags") override val tags: List<String>? = null,
	) : LibrarySearchResponse()

	data class FavoritesData(
		@JsonProperty("favoritesTime") val favoritesTime: Long,
		@JsonProperty("id") override var id: Int?,
		@JsonProperty("latestUpdatedTime") override val latestUpdatedTime: Long,
		@JsonProperty("name") override val name: String,
		@JsonProperty("url") override val url: String,
		@JsonProperty("apiName") override val apiName: String,
		@JsonProperty("type") override var type: TvType? = null,
		@JsonProperty("posterUrl") override var posterUrl: String?,
		@JsonProperty("year") override val year: Int?,
		@JsonProperty("syncData") override val syncData: Map<String, String>? = null,
		@JsonProperty("quality") override var quality: SearchQuality? = null,
		@JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
		@JsonProperty("plot") override val plot: String? = null,
		@JsonProperty("score") override var score: Score? = null,
		@JsonProperty("tags") override val tags: List<String>? = null,
	) : LibrarySearchResponse()

	data class SubscribedData(
		@JsonProperty("subscribedTime") val subscribedTime: Long,
		@JsonProperty("lastSeenEpisodeCount") val lastSeenEpisodeCount: Map<DubStatus, Int?>,
		@JsonProperty("id") override var id: Int?,
		@JsonProperty("latestUpdatedTime") override val latestUpdatedTime: Long,
		@JsonProperty("name") override val name: String,
		@JsonProperty("url") override val url: String,
		@JsonProperty("apiName") override val apiName: String,
		@JsonProperty("type") override var type: TvType? = null,
		@JsonProperty("posterUrl") override var posterUrl: String?,
		@JsonProperty("year") override val year: Int?,
		@JsonProperty("syncData") override val syncData: Map<String, String>? = null,
		@JsonProperty("quality") override var quality: SearchQuality? = null,
		@JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
		@JsonProperty("plot") override val plot: String? = null,
		@JsonProperty("score") override var score: Score? = null,
		@JsonProperty("tags") override val tags: List<String>? = null,
	) : LibrarySearchResponse()

	/** An entry of Cloudstream's continue-watching row. */
	data class ResumeWatchingResult(
		@JsonProperty("name") override val name: String,
		@JsonProperty("url") override val url: String,
		@JsonProperty("apiName") override val apiName: String,
		@JsonProperty("type") override var type: TvType? = null,
		@JsonProperty("posterUrl") override var posterUrl: String?,
		@JsonProperty("watchPos") val watchPos: PosDur?,
		@JsonProperty("id") override var id: Int?,
		@JsonProperty("parentId") val parentId: Int?,
		@JsonProperty("episode") val episode: Int?,
		@JsonProperty("season") val season: Int?,
		@JsonProperty("isFromDownload") val isFromDownload: Boolean,
		@JsonProperty("quality") override var quality: SearchQuality? = null,
		@JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
		@JsonProperty("score") override var score: Score? = null,
	) : SearchResponse

	val currentAccount: String get() = "0"

	var selectedKeyIndex: Int
		get() = 0
		set(@Suppress("UNUSED_PARAMETER") value) = Unit

	var accounts: Array<Account>
		get() = emptyArray()
		set(@Suppress("UNUSED_PARAMETER") value) = Unit

	fun getDefaultAccount(context: Context): Account =
		Account(keyIndex = 0, name = context.applicationInfo.loadLabel(context.packageManager).toString(), defaultImageIndex = 0)

	fun getAccounts(context: Context): List<Account> = listOf(getDefaultAccount(context))

	fun getAllBookmarkedData(): List<BookmarkedData> = emptyList()

	fun getAllFavorites(): List<FavoritesData> = emptyList()

	fun getAllSubscriptions(): List<SubscribedData> = emptyList()

	fun getBookmarkedData(id: Int?): BookmarkedData? = null

	fun getAllResumeStateIds(): List<Int>? = emptyList()

	fun deleteAllResumeStateIds() = Unit

	fun getLastWatched(id: Int?): DownloadObjects.ResumeWatching? = null

	fun removeLastWatched(parentId: Int?) = Unit

	fun getViewPos(id: Int?): PosDur? = null

	fun setViewPos(id: Int?, pos: Long, dur: Long) = Unit

	fun getVideoWatchState(id: Int?): VideoWatchState? = null

	fun getDub(id: Int): DubStatus? = null

	fun getResultWatchState(id: Int): WatchType = WatchType.NONE

	fun setResultSeason(id: Int, value: Int?) = Unit
}

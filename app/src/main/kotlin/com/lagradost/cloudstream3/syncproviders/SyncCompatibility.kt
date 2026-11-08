package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.UiText
import java.util.Date

data class AuthToken(
	@JsonProperty("accessToken") val accessToken: String? = null,
	@JsonProperty("refreshToken") val refreshToken: String? = null,
	/** Unix time in seconds */
	@JsonProperty("accessTokenLifetime") val accessTokenLifetime: Long? = null,
	/** Unix time in seconds */
	@JsonProperty("refreshTokenLifetime") val refreshTokenLifetime: Long? = null,
	@JsonProperty("payload") val payload: String? = null,
)

data class AuthUser(
	@JsonProperty("name") val name: String?,
	@JsonProperty("id") val id: Int,
	@JsonProperty("profilePicture") val profilePicture: String? = null,
	@JsonProperty("profilePictureHeaders") val profilePictureHeaders: Map<String, String>? = null,
)

data class AuthData(
	@JsonProperty("user") val user: AuthUser,
	@JsonProperty("token") val token: AuthToken,
)

/** A service a person signs in to, as Cloudstream models its accounts. */
abstract class AuthAPI {
	open val name: String = "NONE"
	open val idPrefix: String = "NONE"
	open val icon: Int? = null
	open val requiresLogin: Boolean = true
	open val createAccountUrl: String? = null
}

/**
 * A sync service plugins hand to [SyncRepo] or read lists from (CineStream, StreamCenter, sync plugins).
 *
 * Kototoro does not expose Cloudstream account management, so nobody is ever signed in: the repositories hand no
 * credentials, and a service asked without them answers nothing.
 */
open class SyncAPI : AuthAPI() {
	open var requireLibraryRefresh: Boolean = true
	open val mainUrl: String = "NONE"
	open val syncIdName: SyncIdName? = null

	open suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean = false

	open suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? = null

	open suspend fun load(auth: AuthData?, id: String): SyncResult? = null

	open suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? = null

	open suspend fun library(auth: AuthData?): LibraryMetadata? = null

	data class LibraryList(
		val name: UiText,
		val items: List<SearchResponse>,
	)

	data class LibraryMetadata(
		val allLibraryLists: List<LibraryList>,
		val supportedListSorting: Set<Any> = emptySet(),
	)

	abstract class AbstractSyncStatus {
		abstract var status: SyncWatchType
		abstract var score: Score?
		abstract var watchedEpisodes: Int?
		abstract var isFavorite: Boolean?
		abstract var maxEpisodes: Int?
	}

	data class SyncStatus(
		override var status: SyncWatchType,
		override var score: Score?,
		override var watchedEpisodes: Int?,
		override var isFavorite: Boolean? = null,
		override var maxEpisodes: Int? = null,
	) : AbstractSyncStatus()

	data class SyncSearchResult(
		override val name: String,
		override val apiName: String,
		var syncId: String,
		override val url: String,
		override var posterUrl: String?,
		override var type: TvType? = null,
		override var quality: SearchQuality? = null,
		override var posterHeaders: Map<String, String>? = null,
		override var id: Int? = null,
		override var score: Score? = null,
	) : SearchResponse

	data class SyncResult(
		var id: String,
		var totalEpisodes: Int? = null,
		var title: String? = null,
		var publicScore: Score? = null,
		/** In minutes */
		var duration: Int? = null,
		var synopsis: String? = null,
		var airStatus: ShowStatus? = null,
		var nextAiring: NextAiring? = null,
		var studio: List<String>? = null,
		var genres: List<String>? = null,
		var synonyms: List<String>? = null,
		var trailers: List<String>? = null,
		var isAdult: Boolean? = null,
		var posterUrl: String? = null,
		var backgroundPosterUrl: String? = null,
		/** Unix time */
		var startDate: Long? = null,
		/** Unix time */
		var endDate: Long? = null,
		var recommendations: List<SyncSearchResult>? = null,
		var nextSeason: SyncSearchResult? = null,
		var prevSeason: SyncSearchResult? = null,
		var actors: List<ActorData>? = null,
	)

	data class LibraryItem(
		override val name: String,
		override val url: String,
		val syncId: String,
		val episodesCompleted: Int?,
		val episodesTotal: Int?,
		val personalRating: Score?,
		val lastUpdatedUnixTime: Long?,
		override val apiName: String,
		override var type: TvType?,
		override var posterUrl: String?,
		override var posterHeaders: Map<String, String>?,
		override var quality: SearchQuality?,
		val releaseDate: Date?,
		override var id: Int? = null,
		val plot: String? = null,
		override var score: Score? = null,
		val tags: List<String>? = null,
	) : SearchResponse
}

/** A service with the app's accounts on it; never signed in here. */
abstract class AuthRepo(open val api: AuthAPI) {
	val idPrefix: String get() = api.idPrefix
	val name: String get() = api.name
	val icon: Int? get() = api.icon
	val requiresLogin: Boolean get() = api.requiresLogin
	val createAccountUrl: String? get() = api.createAccountUrl
	val isAvailable: Boolean get() = !api.requiresLogin || authUser() != null

	fun authData(): AuthData? = null

	fun authToken(): AuthToken? = authData()?.token

	fun authUser(): AuthUser? = authData()?.user
}

class PlainAuthRepo(api: AuthAPI) : AuthRepo(api)

class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
	val syncIdName: SyncIdName? get() = api.syncIdName

	var requireLibraryRefresh: Boolean
		get() = api.requireLibraryRefresh
		set(value) {
			api.requireLibraryRefresh = value
		}

	suspend fun updateStatus(id: String, newStatus: SyncAPI.AbstractSyncStatus): Result<Boolean> =
		runCatching { authData()?.let { api.updateStatus(it, id, newStatus) } ?: false }

	suspend fun status(id: String): Result<SyncAPI.AbstractSyncStatus?> = runCatching { api.status(authData(), id) }

	suspend fun load(id: String): Result<SyncAPI.SyncResult?> = runCatching { api.load(authData(), id) }

	suspend fun library(): Result<SyncAPI.LibraryMetadata?> = runCatching { api.library(authData()) }
}

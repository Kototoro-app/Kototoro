package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CloudstreamContentMetadata(
    val version: Int = CURRENT_VERSION,
    val type: String? = null,
    val uniqueUrl: String? = null,
    val posterHeaders: Map<String, String> = emptyMap(),
    val year: Int? = null,
    val durationMinutes: Int? = null,
    val quality: String? = null,
    val providerId: Int? = null,
    val mainPageRequestName: String? = null,
    val mainPageRequestData: String? = null,
    val homeRowName: String? = null,
    val horizontalImages: Boolean? = null,
    val logoUrl: String? = null,
    val contentRating: String? = null,
    val showStatus: String? = null,
    val comingSoon: Boolean = false,
    val syncData: Map<String, String> = emptyMap(),
    val actors: List<CloudstreamActorMetadata> = emptyList(),
    val trailers: List<CloudstreamTrailerMetadata> = emptyList(),
    val nextAiring: CloudstreamNextAiringMetadata? = null,
    val seasons: List<CloudstreamSeasonMetadata> = emptyList(),
    val recommendations: List<CloudstreamRecommendationMetadata> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class CloudstreamRecommendationMetadata(
    val name: String,
    val url: String,
    val type: String? = null,
    val posterUrl: String? = null,
    val posterHeaders: Map<String, String> = emptyMap(),
    val score: Float? = null,
)

@Serializable
internal data class CloudstreamActorMetadata(
    val name: String,
    val imageUrl: String? = null,
    val role: String? = null,
    val roleName: String? = null,
    val voiceActorName: String? = null,
    val voiceActorImageUrl: String? = null,
)

@Serializable
internal data class CloudstreamTrailerMetadata(
    val url: String,
    val referer: String? = null,
    val isRaw: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
internal data class CloudstreamNextAiringMetadata(
    val episode: Int,
    val unixTime: Long,
    val season: Int? = null,
)

@Serializable
internal data class CloudstreamSeasonMetadata(
    val season: Int,
    val name: String? = null,
    val displaySeason: Int? = null,
)

@Serializable
internal data class CloudstreamEpisodeMetadata(
    val version: Int = CURRENT_VERSION,
    val linkType: String? = null,
    val dubStatus: String? = null,
    val season: Int? = null,
    val displaySeason: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val score: Float? = null,
    val description: String? = null,
    val runtimeSeconds: Int? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal object CloudstreamMetadataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeContent(metadata: CloudstreamContentMetadata): String = json.encodeToString(metadata)

    fun decodeContent(value: String?): CloudstreamContentMetadata? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<CloudstreamContentMetadata>(value) }.getOrNull()
    }

    fun encodeEpisode(metadata: CloudstreamEpisodeMetadata): String = json.encodeToString(metadata)

    fun decodeEpisode(value: String?): CloudstreamEpisodeMetadata? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<CloudstreamEpisodeMetadata>(value) }.getOrNull()
    }
}

internal fun LoadResponse.toAlternativeTitles(): Set<String> {
    if (this !is AnimeLoadResponse) return emptySet()
    return buildSet {
        engName?.takeIf { it.isNotBlank() }?.let(::add)
        japName?.takeIf { it.isNotBlank() }?.let(::add)
        synonyms.orEmpty().filterTo(this) { it.isNotBlank() }
    }.minus(name)
}

internal fun LoadResponse.toContentMetadata(previous: String?, sourceName: String): String {
    val old = CloudstreamMetadataCodec.decodeContent(previous)
    CloudstreamArtworkHeaders.remember(sourceName, posterUrl, posterHeaders)
    CloudstreamArtworkHeaders.remember(sourceName, backgroundPosterUrl, posterHeaders)
    recommendations.orEmpty().forEach { item ->
        CloudstreamArtworkHeaders.remember(sourceName, item.posterUrl, item.posterHeaders)
    }
    return CloudstreamMetadataCodec.encodeContent(
        CloudstreamContentMetadata(
            type = type.name,
            uniqueUrl = uniqueUrl,
            posterHeaders = posterHeaders
                ?.takeIf { it.isNotEmpty() }
                ?.let(CloudstreamArtworkHeaders::persistable)
                ?: old?.posterHeaders.orEmpty(),
            year = year,
            durationMinutes = duration,
            quality = old?.quality,
            providerId = old?.providerId,
            mainPageRequestName = old?.mainPageRequestName,
            mainPageRequestData = old?.mainPageRequestData,
            homeRowName = old?.homeRowName,
            horizontalImages = old?.horizontalImages,
            logoUrl = logoUrl,
            contentRating = contentRating,
            showStatus = (this as? EpisodeResponse)?.showStatus?.name,
            comingSoon = comingSoon,
            syncData = syncData,
            actors = actors.orEmpty().map { actor ->
                CloudstreamActorMetadata(
                    name = actor.actor.name,
                    imageUrl = actor.actor.image,
                    role = actor.role?.name,
                    roleName = actor.roleString,
                    voiceActorName = actor.voiceActor?.name,
                    voiceActorImageUrl = actor.voiceActor?.image,
                )
            },
            trailers = trailers.map { trailer ->
                CloudstreamTrailerMetadata(
                    url = trailer.extractorUrl,
                    referer = trailer.referer,
                    isRaw = trailer.raw,
                    headers = CloudstreamArtworkHeaders.persistable(trailer.headers),
                )
            },
            nextAiring = (this as? EpisodeResponse)?.nextAiring?.let { airing ->
                CloudstreamNextAiringMetadata(
                    episode = airing.episode,
                    unixTime = airing.unixTime,
                    season = airing.season,
                )
            },
            seasons = (this as? EpisodeResponse)?.seasonNames.orEmpty().map { season ->
                CloudstreamSeasonMetadata(
                    season = season.season,
                    name = season.name,
                    displaySeason = season.displaySeason,
                )
            },
            recommendations = recommendations.orEmpty().map { item ->
                CloudstreamRecommendationMetadata(
                    name = item.name,
                    url = item.url,
                    type = item.type?.name,
                    posterUrl = item.posterUrl,
                    posterHeaders = CloudstreamArtworkHeaders.persistable(item.posterHeaders),
                    score = item.score.toKotoRating(),
                )
            },
        ),
    )
}

internal fun Score?.toKotoRating(): Float? = this?.toInt(100)?.div(100f)

internal object CloudstreamArtworkHeaders {
    private val runtimeHeaders = object : LinkedHashMap<String, Map<String, String>>(MAX_RUNTIME_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Map<String, String>>): Boolean {
            return size > MAX_RUNTIME_ENTRIES
        }
    }

    fun remember(sourceName: String, artworkUrl: String?, headers: Map<String, String>?) {
        if (artworkUrl.isNullOrBlank() || headers.isNullOrEmpty()) return
        synchronized(runtimeHeaders) {
            runtimeHeaders[key(sourceName, artworkUrl)] = headers
        }
    }

    fun resolve(
        sourceName: String,
        artworkUrl: String,
        persistedHeaders: Map<String, String>,
    ): Map<String, String> = persistedHeaders + synchronized(runtimeHeaders) {
        runtimeHeaders[key(sourceName, artworkUrl)].orEmpty()
    }

    fun persistable(headers: Map<String, String>?): Map<String, String> {
        return headers.orEmpty().filterKeys { name ->
            name.lowercase() !in SENSITIVE_HEADER_NAMES
        }
    }

    private fun key(sourceName: String, artworkUrl: String): String = "$sourceName\n$artworkUrl"

    private val SENSITIVE_HEADER_NAMES = setOf(
        "authorization",
        "cookie",
        "proxy-authorization",
        "x-api-key",
    )

    private const val MAX_RUNTIME_ENTRIES = 2_048
}

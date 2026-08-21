package org.skepsun.kototoro.cloudstream.model

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Prerelease
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

data class CloudstreamSource(
    val api: MainAPI,
    val pluginFileName: String,
    val pluginPackageName: String,
) : ContentSource {

    override val name: String = buildName(pluginPackageName, api.name)

    override val locale: String
        get() = api.lang

    override val contentType: ContentType
        get() = when {
            api.supportedTypes.any { it.isVideoLike() } -> ContentType.VIDEO
            else -> ContentType.OTHER
        }

    val displayName: String
        get() = api.name

    companion object {
        private fun buildName(packageName: String, providerName: String): String {
            return "CLOUDSTREAM_${packageName}_${providerName}"
        }
    }
}

@OptIn(Prerelease::class)
private fun com.lagradost.cloudstream3.TvType.isVideoLike(): Boolean {
    return when (this) {
        com.lagradost.cloudstream3.TvType.Movie,
        com.lagradost.cloudstream3.TvType.AnimeMovie,
        com.lagradost.cloudstream3.TvType.TvSeries,
        com.lagradost.cloudstream3.TvType.Cartoon,
        com.lagradost.cloudstream3.TvType.Anime,
        com.lagradost.cloudstream3.TvType.OVA,
        com.lagradost.cloudstream3.TvType.Torrent,
        com.lagradost.cloudstream3.TvType.Documentary,
        com.lagradost.cloudstream3.TvType.AsianDrama,
        com.lagradost.cloudstream3.TvType.Live,
        com.lagradost.cloudstream3.TvType.NSFW,
        com.lagradost.cloudstream3.TvType.Others,
        com.lagradost.cloudstream3.TvType.AudioBook,
        com.lagradost.cloudstream3.TvType.CustomMedia,
        com.lagradost.cloudstream3.TvType.Audio,
        com.lagradost.cloudstream3.TvType.Podcast,
        com.lagradost.cloudstream3.TvType.Video -> true
        com.lagradost.cloudstream3.TvType.Music -> false
    }
}

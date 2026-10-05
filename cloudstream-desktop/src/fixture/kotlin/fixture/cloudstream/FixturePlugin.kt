package fixture.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Independently authored plugin with the shape of real ones (a `Plugin` registering a `MainAPI` in `load(Context)`);
 * every answer is canned, nothing touches the network. The test d8s it into a `.cs3` like the repositories' builds.
 */
class FixturePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FixtureProvider())
    }
}

class FixtureProvider : MainAPI() {
    override var name = "Fixture Stream"
    override var mainUrl = "https://fixture.invalid"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val mainPage = mainPageOf("popular" to "热门", "latest" to "最新")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        newHomePageResponse(request.name, listOf(
            newTvSeriesSearchResponse("${request.name} 剧集 $page", "/show/${request.data}-$page", TvType.TvSeries) {
                posterUrl = "$mainUrl/poster/${request.data}-$page.jpg"
            },
        ), hasNext = page < 2)

    override suspend fun search(query: String): List<SearchResponse> =
        listOf(newMovieSearchResponse("电影 $query", "/movie/$query", TvType.Movie))

    override suspend fun load(url: String): LoadResponse = if (url.contains("/show/")) {
        newTvSeriesLoadResponse("测试剧集", url, TvType.TvSeries, listOf(
            newEpisode("episode-1") { name = "第一集"; season = 1; episode = 1 },
            newEpisode("episode-2") { name = "第二集"; season = 1; episode = 2 },
        )) {
            plot = "剧集简介"
            tags = listOf("剧情", "悬疑")
        }
    } else {
        newMovieLoadResponse("测试电影", url, TvType.Movie, "movie-data") { plot = "电影简介" }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // The library makes episode data absolute (fixUrl), as for real plugins.
        val id = data.substringAfterLast('/')
        subtitleCallback(newSubtitleFile("zh", "$mainUrl/subtitles/$id.vtt"))
        callback(newExtractorLink(name, "高清", "$mainUrl/streams/$id.m3u8", ExtractorLinkType.M3U8) {
            quality = 1080
            referer = mainUrl
            headers = mapOf("X-Fixture" to id)
        })
        return true
    }
}

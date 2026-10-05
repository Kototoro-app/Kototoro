package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

open class YoutubeExtractor : ExtractorApi() {
    override val name: String = "YouTube"
    override val mainUrl: String = "https://www.youtube.com"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Intentionally left blank: the host app does not ship NewPipe, so this
        // compatibility stub only satisfies legacy Cloudstream runtime linkage.
    }
}

class YoutubeMobileExtractor : YoutubeExtractor() {
    override val mainUrl: String = "https://m.youtube.com"
}

class YoutubeNoCookieExtractor : YoutubeExtractor() {
    override val mainUrl: String = "https://www.youtube-nocookie.com"
}

class YoutubeShortLinkExtractor : YoutubeExtractor() {
    override val mainUrl: String = "https://youtu.be"
}

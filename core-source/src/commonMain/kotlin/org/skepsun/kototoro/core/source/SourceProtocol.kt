package org.skepsun.kototoro.core.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SOURCE_PROTOCOL_VERSION = 1

val SourceProtocolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "operation"
}

@Serializable
data class SourceRequest(val version: Int, val requestId: String, val call: SourceCall)

@Serializable
sealed interface SourceCall {
    @Serializable @SerialName("sources") data object Sources : SourceCall
    @Serializable @SerialName("describe") data class Describe(val sourceName: String) : SourceCall
    @Serializable @SerialName("filters") data class Filters(val sourceName: String) : SourceCall
    @Serializable @SerialName("dynamicFilters") data class DynamicFilters(val sourceName: String) : SourceCall
    @Serializable @SerialName("preferences") data class Preferences(val sourceName: String) : SourceCall
    @Serializable @SerialName("updatePreference") data class UpdatePreference(
        val sourceName: String, val revision: String, val nodeId: String, val value: SourcePreferenceValue,
    ) : SourceCall
    @Serializable @SerialName("list") data class ListContent(
        val sourceName: String,
        val offset: Int,
        val order: String? = null,
        val filter: SourceFilter? = null,
    ) : SourceCall
    @Serializable @SerialName("details") data class Details(
        val content: SourceContent,
        val fetchMode: SourceDetailsFetchMode = SourceDetailsFetchMode.ALLOW_CACHE,
    ) : SourceCall
    @Serializable @SerialName("pages") data class Pages(
        val chapter: SourceChapter,
        val nextChapterUrl: String? = null,
    ) : SourceCall
    @Serializable @SerialName("pageUrl") data class PageUrl(val page: SourcePage) : SourceCall
    @Serializable @SerialName("chapterContent") data class ChapterContent(
        val chapter: SourceChapter,
        val nextChapterUrl: String? = null,
    ) : SourceCall
    @Serializable @SerialName("image") data class Image(val page: SourcePage) : SourceCall
    @Serializable @SerialName("cover") data class Cover(val content: SourceContent, val large: Boolean = false) : SourceCall
    @Serializable @SerialName("related") data class Related(val content: SourceContent) : SourceCall
    /** Where to send the user for browser verification, with the User-Agent the runtime's requests carry. */
    @Serializable @SerialName("browserSession") data class BrowserSession(val sourceName: String) : SourceCall
    /** Cookies a browser received for [url] (`Set-Cookie` syntax), e.g. a Cloudflare clearance, for later requests. */
    @Serializable @SerialName("importCookies") data class ImportCookies(
        val sourceName: String,
        val url: String,
        val cookies: List<String>,
    ) : SourceCall
}

@Serializable
sealed interface SourceResult {
    @Serializable @SerialName("sources") data class Sources(val sources: List<SourceRef>) : SourceResult
    @Serializable @SerialName("describe") data class Descriptor(val descriptor: SourceDescriptor) : SourceResult
    @Serializable @SerialName("filters") data class Filters(val options: SourceFilterOptions) : SourceResult
    @Serializable @SerialName("dynamicFilters") data class DynamicFilters(val filters: SourceDynamicFilters) : SourceResult
    @Serializable @SerialName("preferences") data class Preferences(val screen: SourcePreferenceScreen) : SourceResult
    @Serializable @SerialName("updatePreference") data class PreferenceUpdate(val update: SourcePreferenceUpdate) : SourceResult
    @Serializable @SerialName("list") data class ListContent(val content: List<SourceContent>) : SourceResult
    @Serializable @SerialName("details") data class Details(val content: SourceContent) : SourceResult
    @Serializable @SerialName("pages") data class Pages(val pages: List<SourcePage>) : SourceResult
    @Serializable @SerialName("pageUrl") data class PageUrl(val url: String) : SourceResult
    @Serializable @SerialName("chapterContent") data class ChapterContent(val content: SourceChapterContent?) : SourceResult
    @Serializable @SerialName("image") data class Image(val artifact: SourceImageArtifact) : SourceResult
    @Serializable @SerialName("cover") data class Cover(val artifact: SourceCoverArtifact) : SourceResult
    @Serializable @SerialName("related") data class Related(val content: List<SourceContent>) : SourceResult
    @Serializable @SerialName("browserSession") data class BrowserSession(val session: SourceBrowserSession) : SourceResult
    @Serializable @SerialName("importCookies") data class CookiesImported(val count: Int) : SourceResult
}

/** [userAgent] is null when the runtime cannot tell; a browser should then keep its own. */
@Serializable
data class SourceBrowserSession(val url: String, val userAgent: String? = null)

@Serializable
enum class SourceErrorCode {
    INVALID_REQUEST,
    UNSUPPORTED_VERSION,
    SOURCE_UNAVAILABLE,
    INVALID_ARGUMENT,
    UNSUPPORTED_OPERATION,
    RUNTIME_FAILURE,
    /** The site answered with a browser challenge (Cloudflare and the like); pass it in a browser, then retry. */
    CHALLENGE_REQUIRED,
    /** The site answered HTTP 429; wait [SourceError.retryAfterMillis] when given before asking it again. */
    RATE_LIMITED,
}

@Serializable
data class SourceError(
    val code: SourceErrorCode,
    val message: String,
    /** [SourceErrorCode.CHALLENGE_REQUIRED] only: the challenged site's origin (scheme and host, no path or query). */
    val url: String? = null,
    /** [SourceErrorCode.RATE_LIMITED] only: how long the site asked to wait, when it said. */
    val retryAfterMillis: Long? = null,
)

@Serializable
data class SourceResponse(
    val version: Int = SOURCE_PROTOCOL_VERSION,
    val requestId: String?,
    val result: SourceResult? = null,
    val error: SourceError? = null,
) {
    init {
        require((result == null) != (error == null)) { "Response must contain exactly one result or error" }
    }
}

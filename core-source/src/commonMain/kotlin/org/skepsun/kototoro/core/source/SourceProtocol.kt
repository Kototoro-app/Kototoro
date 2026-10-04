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
}

@Serializable
enum class SourceErrorCode {
    INVALID_REQUEST,
    UNSUPPORTED_VERSION,
    SOURCE_UNAVAILABLE,
    INVALID_ARGUMENT,
    UNSUPPORTED_OPERATION,
    RUNTIME_FAILURE,
}

@Serializable
data class SourceError(val code: SourceErrorCode, val message: String)

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

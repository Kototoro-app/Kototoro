package org.skepsun.kototoro.core.source

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

class SourceRemoteException(val error: SourceError, val requestId: String?) : Exception(error.message) {
    constructor(error: SourceError) : this(error, null)
}
class SourceProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The caller supplies unique IDs and owns transport lifetime and cancellation. */
class SourceProtocolClient(
    private val transport: SourceTransport,
    private val requestId: () -> String,
) : SourceRuntime {
    override suspend fun getSources() = call<SourceResult.Sources>(SourceCall.Sources).sources
    override suspend fun describe(sourceName: String) =
        call<SourceResult.Descriptor>(SourceCall.Describe(sourceName)).descriptor
    override suspend fun getFilterOptions(sourceName: String) =
        call<SourceResult.Filters>(SourceCall.Filters(sourceName)).options
    override suspend fun getDynamicFilters(sourceName: String) =
        call<SourceResult.DynamicFilters>(SourceCall.DynamicFilters(sourceName)).filters
    override suspend fun getPreferences(sourceName: String) =
        call<SourceResult.Preferences>(SourceCall.Preferences(sourceName)).screen
    override suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ) = call<SourceResult.PreferenceUpdate>(SourceCall.UpdatePreference(sourceName, revision, nodeId, value)).update
    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?) =
        call<SourceResult.ListContent>(SourceCall.ListContent(sourceName, offset, order, filter)).content
    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) =
        call<SourceResult.Details>(SourceCall.Details(content, fetchMode)).content
    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?) =
        call<SourceResult.Pages>(SourceCall.Pages(chapter, nextChapterUrl)).pages
    override suspend fun getPageUrl(page: SourcePage) = call<SourceResult.PageUrl>(SourceCall.PageUrl(page)).url
    override suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?) =
        call<SourceResult.ChapterContent>(SourceCall.ChapterContent(chapter, nextChapterUrl)).content
    override suspend fun fetchImage(page: SourcePage) = call<SourceResult.Image>(SourceCall.Image(page)).artifact
    override suspend fun fetchCover(content: SourceContent, large: Boolean) =
        call<SourceResult.Cover>(SourceCall.Cover(content, large)).artifact
    override suspend fun getRelated(content: SourceContent) =
        call<SourceResult.Related>(SourceCall.Related(content)).content

    private suspend inline fun <reified T : SourceResult> call(call: SourceCall): T {
        currentCoroutineContext().ensureActive()
        val id = requestId()
        require(id.isNotEmpty()) { "Request ID must not be empty" }
        val encoded = SourceProtocolJson.encodeToString(SourceRequest(SOURCE_PROTOCOL_VERSION, id, call))
        val body = transport.exchange(encoded)
        currentCoroutineContext().ensureActive()
        val response = try {
            SourceProtocolJson.decodeFromString<SourceResponse>(body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SourceProtocolException("Invalid source response", error)
        }
        if (response.version != SOURCE_PROTOCOL_VERSION || response.requestId != id) {
            throw SourceProtocolException("Source response version or request ID mismatch")
        }
        response.error?.let { throw SourceRemoteException(it, response.requestId) }
        return response.result as? T ?: throw SourceProtocolException("Unexpected source result type")
    }
}

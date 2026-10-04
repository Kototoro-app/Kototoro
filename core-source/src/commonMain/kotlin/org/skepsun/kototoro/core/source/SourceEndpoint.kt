package org.skepsun.kototoro.core.source

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.cancellation.CancellationException

fun interface SourceTransport {
    suspend fun exchange(request: String): String
}

/** One JSON entry point for a local Android host, a desktop JVM host or a native bridge. */
class SourceEndpoint(
    private val runtime: SourceRuntime,
    private val reportFailure: (SourceRequest, Exception) -> Unit,
) : SourceTransport {
    constructor(runtime: SourceRuntime) : this(runtime, { _, _ -> })
    override suspend fun exchange(request: String): String {
        currentCoroutineContext().ensureActive()
        val envelope = try {
            SourceProtocolJson.parseToJsonElement(request) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val requestId = (envelope?.get("requestId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = (envelope?.get("version") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (version != null && version != SOURCE_PROTOCOL_VERSION) {
            return failure(requestId, SourceErrorCode.UNSUPPORTED_VERSION, "Unsupported source protocol version")
        }
        val decoded = try {
            if (requestId.isNullOrEmpty() || version == null) {
                return failure(requestId, SourceErrorCode.INVALID_REQUEST, "Invalid source request")
            }
            SourceProtocolJson.decodeFromString<SourceRequest>(request)
        } catch (_: IllegalArgumentException) {
            return failure(requestId, SourceErrorCode.INVALID_REQUEST, "Invalid source request")
        }
        val response = try {
            val result = when (val call = decoded.call) {
                SourceCall.Sources -> SourceResult.Sources(runtime.getSources())
                is SourceCall.Describe -> SourceResult.Descriptor(runtime.describe(call.sourceName))
                is SourceCall.Filters -> SourceResult.Filters(runtime.getFilterOptions(call.sourceName))
                is SourceCall.DynamicFilters -> SourceResult.DynamicFilters(runtime.getDynamicFilters(call.sourceName))
                is SourceCall.Preferences -> SourceResult.Preferences(runtime.getPreferences(call.sourceName))
                is SourceCall.UpdatePreference -> SourceResult.PreferenceUpdate(runtime.updatePreference(
                    call.sourceName, call.revision, call.nodeId, call.value,
                ))
                is SourceCall.ListContent -> SourceResult.ListContent(
                    runtime.getList(call.sourceName, call.offset, call.order, call.filter),
                )
                is SourceCall.Details -> SourceResult.Details(runtime.getDetails(call.content, call.fetchMode))
                is SourceCall.Pages -> SourceResult.Pages(runtime.getPages(call.chapter, call.nextChapterUrl))
                is SourceCall.PageUrl -> SourceResult.PageUrl(runtime.getPageUrl(call.page))
                is SourceCall.ChapterContent -> SourceResult.ChapterContent(
                    runtime.getChapterContent(call.chapter, call.nextChapterUrl),
                )
                is SourceCall.Image -> SourceResult.Image(runtime.fetchImage(call.page))
                is SourceCall.Cover -> SourceResult.Cover(runtime.fetchCover(call.content, call.large))
                is SourceCall.Related -> SourceResult.Related(runtime.getRelated(call.content))
            }
            currentCoroutineContext().ensureActive()
            SourceProtocolJson.encodeToString(SourceResponse(requestId = decoded.requestId, result = result))
        } catch (error: CancellationException) {
            throw error
        } catch (_: SourceUnavailableException) {
            failure(decoded.requestId, SourceErrorCode.SOURCE_UNAVAILABLE, "Source unavailable")
        } catch (_: SourceInvalidArgumentException) {
            failure(decoded.requestId, SourceErrorCode.INVALID_ARGUMENT, "Invalid source argument")
        } catch (_: SourceOperationUnsupportedException) {
            failure(decoded.requestId, SourceErrorCode.UNSUPPORTED_OPERATION, "Unsupported source operation")
        } catch (error: Exception) {
            // Do not expose parser stack traces, tokens or raw request URLs through the wire protocol.
            runCatching { reportFailure(decoded, error) }
            failure(decoded.requestId, SourceErrorCode.RUNTIME_FAILURE, "Source operation failed")
        }
        currentCoroutineContext().ensureActive()
        return response
    }

    private fun failure(requestId: String?, code: SourceErrorCode, message: String): String =
        SourceProtocolJson.encodeToString(SourceResponse(requestId = requestId, error = SourceError(code, message)))
}

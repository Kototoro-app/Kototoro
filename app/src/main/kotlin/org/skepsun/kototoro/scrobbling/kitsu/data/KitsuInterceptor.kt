package org.skepsun.kototoro.scrobbling.kitsu.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.parsers.util.mimeType
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

class KitsuInterceptor(private val storage: ScrobblerStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val sourceRequest = chain.request()
        val request = sourceRequest.newBuilder()
        request.header(CommonHeaders.CONTENT_TYPE, VND_JSON)
        request.header(CommonHeaders.ACCEPT, VND_JSON)
        val isAuthRequest = sourceRequest.url.pathSegments.contains("oauth")
        if (!isAuthRequest) {
            storage.accessToken?.let {
                request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
            }
        }
        val response = chain.proceed(request.build())
        if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            response.closeQuietly()
            throw ScrobblerAuthRequiredException(ScrobblerService.KITSU)
        }
        if (response.mimeType?.toMediaTypeOrNull()?.subtype == SUBTYPE_HTML) {
            val message = runCatchingCancellable {
                response.parseHtml().title().nullIfEmpty()
            }.onFailure {
                response.closeQuietly()
            }.getOrNull() ?: "Invalid response (${response.code})"
            throw IOException(message)
        }
        return response
    }

    companion object {

        const val VND_JSON = "application/vnd.api+json"
        const val SUBTYPE_HTML = "html"
    }
}

package org.skepsun.kototoro.scrobbling.mal.data

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.parsers.util.mimeType
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val JSON = "application/json"
private const val HTML = "text/html"
private const val HEADER_MAL_CLIENT_ID = "X-MAL-CLIENT-ID"

class MALInterceptor(
    private val storage: ScrobblerStorage,
    private val clientId: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val sourceRequest = chain.request()
        val request = sourceRequest.newBuilder()
        request.header(CommonHeaders.CONTENT_TYPE, JSON)
        request.header(CommonHeaders.ACCEPT, JSON)
        val isAuthRequest = sourceRequest.url.pathSegments.contains("token")
        if (!isAuthRequest) {
            storage.accessToken?.let {
                request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
            }
            request.header(HEADER_MAL_CLIENT_ID, clientId)
        }
        val response = chain.proceed(request.build())
        if (!isAuthRequest && (response.code == HttpURLConnection.HTTP_UNAUTHORIZED || response.code == HttpURLConnection.HTTP_FORBIDDEN)) {
            throw ScrobblerAuthRequiredException(ScrobblerService.MAL)
        }
        if (response.mimeType == HTML) {
            throw IOException(response.parseHtml().title())
        }
        return response
    }

}


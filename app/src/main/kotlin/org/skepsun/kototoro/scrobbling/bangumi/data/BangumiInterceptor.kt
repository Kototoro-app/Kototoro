package org.skepsun.kototoro.scrobbling.bangumi.data

import okhttp3.Interceptor
import okhttp3.Response
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val JSON = "application/json"

class BangumiInterceptor(private val storage: ScrobblerStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val sourceRequest = chain.request()
        val request = sourceRequest.newBuilder()
        // Only set default headers if not already provided by the caller (e.g., browser scraping)
        if (sourceRequest.header(CommonHeaders.USER_AGENT) == null) {
            request.header(CommonHeaders.USER_AGENT, "Kototoro/1.0 (Android) (https://github.com/Kototoro-app/Kototoro)")
        }
        if (sourceRequest.header(CommonHeaders.ACCEPT) == null) {
            request.header(CommonHeaders.ACCEPT, JSON)
        }

        val isAuthRequest = sourceRequest.url.pathSegments.contains("oauth")
        if (!isAuthRequest) {
            storage.accessToken?.let {
                request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
            }
        }

        val response = chain.proceed(request.build())
        if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw ScrobblerAuthRequiredException(ScrobblerService.BANGUMI)
        }
        return response
    }
}

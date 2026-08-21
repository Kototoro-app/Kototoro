package org.skepsun.kototoro.scrobbling.simkl.data

import okhttp3.Interceptor
import okhttp3.Response
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val HEADER_SIMKL_API_KEY = "simkl-api-key"
private const val JSON = "application/json"
private const val SIMKL_HOST = "api.simkl.com"
private const val APP_NAME = "Kototoro"

class SimklInterceptor(
    private val storage: ScrobblerStorage,
    private val clientId: String,
    private val appVersion: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val sourceRequest = chain.request()
        val url = sourceRequest.url.let { originalUrl ->
            if (!originalUrl.host.equals(SIMKL_HOST, ignoreCase = true)) {
                originalUrl
            } else {
                originalUrl.newBuilder().apply {
                    if (originalUrl.queryParameter("client_id").isNullOrBlank()) {
                        addQueryParameter("client_id", clientId)
                    }
                    if (originalUrl.queryParameter("app-name").isNullOrBlank()) {
                        addQueryParameter("app-name", APP_NAME)
                    }
                    if (originalUrl.queryParameter("app-version").isNullOrBlank()) {
                        addQueryParameter("app-version", appVersion)
                    }
                }.build()
            }
        }
        val request = sourceRequest.newBuilder()
            .url(url)
            .header(CommonHeaders.ACCEPT, JSON)
            .header(CommonHeaders.CONTENT_TYPE, JSON)
            .header(HEADER_SIMKL_API_KEY, clientId)
            .header(CommonHeaders.USER_AGENT, "$APP_NAME/$appVersion")
        if (!sourceRequest.url.encodedPath.contains("/oauth/")) {
            storage.accessToken?.let {
                request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
            }
        }
        val response = chain.proceed(request.build())
        if (!sourceRequest.url.encodedPath.contains("/oauth/") &&
            (response.code == HttpURLConnection.HTTP_UNAUTHORIZED || response.code == HttpURLConnection.HTTP_FORBIDDEN)
        ) {
            throw ScrobblerAuthRequiredException(ScrobblerService.SIMKL)
        }
        return response
    }
}

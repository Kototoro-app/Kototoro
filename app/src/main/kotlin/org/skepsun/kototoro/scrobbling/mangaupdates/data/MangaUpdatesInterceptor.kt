package org.skepsun.kototoro.scrobbling.mangaupdates.data

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.closeQuietly
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val MANGAUPDATES_API_ROOT = "https://api.mangaupdates.com/"
private const val MANGAUPDATES_WEB_ROOT = "https://www.mangaupdates.com/"

class MangaUpdatesInterceptor(
    private val storage: ScrobblerStorage,
    private val cookieJar: MutableCookieJar,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val sourceRequest = chain.request()
        val request = sourceRequest.newBuilder()

        val isAuthRequest = sourceRequest.url.pathSegments.contains("account") && sourceRequest.url.pathSegments.contains("login")
        if (!isAuthRequest) {
            storage.accessToken?.let {
                request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
            }
        }

        val response = chain.proceed(request.build())

        if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            storage.accessToken = null
            storage.user = null
            cookieJar.removeCookies(MANGAUPDATES_API_ROOT.toHttpUrl(), null)
            cookieJar.removeCookies(MANGAUPDATES_WEB_ROOT.toHttpUrl(), null)
            response.closeQuietly()
            throw ScrobblerAuthRequiredException(ScrobblerService.MANGAUPDATES)
        }

        return response
    }
}

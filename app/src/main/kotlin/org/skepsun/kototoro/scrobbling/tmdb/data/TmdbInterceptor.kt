package org.skepsun.kototoro.scrobbling.tmdb.data

import okhttp3.Interceptor
import okhttp3.Response
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val TMDB_HOST = "api.themoviedb.org"
private const val JSON = "application/json"

/** TMDB's v3 API identifies the app by `api_key` and the user by the `session_id` that [TmdbRepository.authorize] stores. */
class TmdbInterceptor(
    private val storage: ScrobblerStorage,
    private val apiKey: String,
    private val appVersion: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val source = chain.request()
        if (!source.url.host.equals(TMDB_HOST, ignoreCase = true)) {
            return chain.proceed(source)
        }
        val url = source.url.newBuilder().apply {
            if (source.url.queryParameter("api_key").isNullOrBlank()) addQueryParameter("api_key", apiKey)
            val session = storage.accessToken
            if (!session.isNullOrBlank() && source.url.queryParameter("session_id").isNullOrBlank()) {
                addQueryParameter("session_id", session)
            }
        }.build()
        val request = source.newBuilder()
            .url(url)
            .header(CommonHeaders.ACCEPT, JSON)
            .header(CommonHeaders.USER_AGENT, "Kototoro/$appVersion")
            .build()
        val response = chain.proceed(request)
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && storage.accessToken != null) {
            throw ScrobblerAuthRequiredException(ScrobblerService.TMDB)
        }
        return response
    }
}

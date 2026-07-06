package org.skepsun.kototoro.scrobbling.shikimori.data

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.parsers.network.GZipOptions
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val USER_AGENT_SHIKIMORI = "Kototoro"

class ShikimoriInterceptor(private val storage: ScrobblerStorage) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val sourceRequest = chain.request()
		val request = sourceRequest.newBuilder()
		request.tag(GZipOptions::class.java, GZipOptions(skip = true))
		request.removeHeader(CommonHeaders.CONTENT_ENCODING)
		request.header(CommonHeaders.CACHE_CONTROL, "no-cache")
		request.header(CommonHeaders.USER_AGENT, USER_AGENT_SHIKIMORI)
		val isAuthRequest = sourceRequest.url.pathSegments.contains("oauth")
		if (!isAuthRequest) {
			storage.accessToken?.let {
				request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
			}
		}
		val response = chain.proceed(request.build())
		if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
			throw ScrobblerAuthRequiredException(ScrobblerService.SHIKIMORI)
		}
		if (!response.isSuccessful && !response.isRedirect) {
			val errorBody = response.body.string()
			throw IOException("${response.code} ${response.message}: $errorBody")
		}
		return response
	}
}

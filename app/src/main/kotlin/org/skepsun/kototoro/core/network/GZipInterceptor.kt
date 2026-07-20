package org.skepsun.kototoro.core.network

import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.Response
import okio.IOException
import org.skepsun.kototoro.core.exceptions.WrapperIOException
import org.skepsun.kototoro.core.network.CommonHeaders.CONTENT_ENCODING
import org.skepsun.kototoro.parsers.network.GZipOptions
import java.net.HttpURLConnection

class GZipInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		return try {
			val request = chain.request()
			val skipGZip = request.tag(GZipOptions::class.java)?.skip == true
			if (
				request.body is MultipartBody ||
				request.header(CONTENT_ENCODING) != null ||
				skipGZip ||
				(request.method != "GET" && request.method != "HEAD")
			) {
				chain.proceed(request)
			} else {
				val response = chain.proceed(request)
				if (response.code != HttpURLConnection.HTTP_BAD_REQUEST &&
					response.code != HttpURLConnection.HTTP_UNSUPPORTED_TYPE
				) {
					response
				} else {
					response.close()
					chain.proceed(
						request.newBuilder()
							.header(CONTENT_ENCODING, "gzip")
							.build(),
					)
				}
			}
		} catch (e: IOException) {
			throw e
		} catch (e: Exception) {
			throw WrapperIOException(e)
		}
	}
}

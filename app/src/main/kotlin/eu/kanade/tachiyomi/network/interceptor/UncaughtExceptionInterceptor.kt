package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Required by newer Mihon extensions as the first interceptor in the default client.
 */
class UncaughtExceptionInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		return try {
			chain.proceed(chain.request())
		} catch (error: Exception) {
			if (error is IOException) {
				throw error
			}
			throw IOException(error)
		}
	}
}

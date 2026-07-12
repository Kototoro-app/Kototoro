package org.skepsun.kototoro.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

class CloudFlareInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val source = request.tag(ContentSource::class.java) 
			?: request.headers[org.skepsun.kototoro.core.network.CommonHeaders.MANGA_SOURCE]?.let { org.skepsun.kototoro.core.model.ContentSource(it) }
		return when (CloudFlareHelper.checkResponseForProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> {
				val policy = request.tag(CloudFlareHandlingPolicy::class.java)
				if (policy?.allowBlockedResponse == true) {
					Log.w(
						TAG,
						"CloudFlare blocked response allowed by request policy: url=${request.url} source=${source?.name}",
					)
					response
				} else {
					response.closeThrowing(
						CloudFlareBlockedException(
							url = request.url.toString(),
							source = source,
						),
					)
				}
			}

			CloudFlareHelper.PROTECTION_CAPTCHA -> response.closeThrowing(
				CloudFlareProtectedException(
					url = CloudFlareHelper.getChallengeUrl(request.url.toString()),
					source = source,
					headers = request.headers,
				),
			)

			else -> response
		}
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}

	private companion object {
		const val TAG = "CloudFlareInterceptor"
	}
}

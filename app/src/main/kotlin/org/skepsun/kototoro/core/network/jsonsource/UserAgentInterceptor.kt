package org.skepsun.kototoro.core.network.jsonsource

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Interceptor that adds or rotates User-Agent headers
 * Helps avoid detection by rotating through different User-Agents
 */
class UserAgentInterceptor @Inject constructor(
    private val userAgentManager: UserAgentManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // If User-Agent is already set, don't override it
        if (originalRequest.header("User-Agent") != null) {
            return chain.proceed(originalRequest)
        }

        // Add a rotated User-Agent
        val requestWithUserAgent = originalRequest.newBuilder()
            .header("User-Agent", userAgentManager.getUserAgent())
            .build()

        return chain.proceed(requestWithUserAgent)
    }
}

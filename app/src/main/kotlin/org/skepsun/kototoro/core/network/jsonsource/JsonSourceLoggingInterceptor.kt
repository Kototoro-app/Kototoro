package org.skepsun.kototoro.core.network.jsonsource

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Logging interceptor for JSON source requests
 * Logs request and response details for debugging
 */
class JsonSourceLoggingInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "JsonSourceHttp"
        private const val MAX_BODY_LOG_LENGTH = 1000
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // Log request
        Log.d(TAG, "→ ${request.method} ${request.url}")
        request.headers.forEach { (name, value) ->
            if (name.equals("Authorization", ignoreCase = true) ||
                name.equals("Cookie", ignoreCase = true)) {
                Log.d(TAG, "  $name: [REDACTED]")
            } else {
                Log.d(TAG, "  $name: $value")
            }
        }

        // Execute request
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "← ${request.method} ${request.url} FAILED after ${duration}ms", e)
            throw e
        }

        // Log response
        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "← ${response.code} ${request.url} (${duration}ms)")

        // Log response headers
        response.headers.forEach { (name, value) ->
            if (name.equals("Set-Cookie", ignoreCase = true)) {
                Log.d(TAG, "  $name: [REDACTED]")
            } else {
                Log.d(TAG, "  $name: $value")
            }
        }

        // Log response body (if text and not too large)
        val contentType = response.header("Content-Type")
        if (contentType != null && isTextContent(contentType)) {
            try {
                val source = response.body?.source()
                source?.request(Long.MAX_VALUE) // Buffer the entire body
                val buffer = source?.buffer
                val bodyString = buffer?.clone()?.readUtf8()

                if (bodyString != null) {
                    if (bodyString.length > MAX_BODY_LOG_LENGTH) {
                        Log.d(TAG, "  Body: ${bodyString.take(MAX_BODY_LOG_LENGTH)}... (truncated)")
                    } else {
                        Log.d(TAG, "  Body: $bodyString")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "  Body: [Failed to read: ${e.message}]")
            }
        }

        return response
    }

    private fun isTextContent(contentType: String): Boolean {
        val lowerType = contentType.lowercase()
        return lowerType.contains("text/") ||
               lowerType.contains("application/json") ||
               lowerType.contains("application/xml") ||
               lowerType.contains("application/javascript")
    }
}

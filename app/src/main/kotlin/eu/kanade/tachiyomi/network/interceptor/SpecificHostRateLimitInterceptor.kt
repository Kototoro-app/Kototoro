package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limit interceptor for specific hosts.
 * 
 * This is a compatibility shim for Mihon extensions that use SpecificHostRateLimitInterceptor.
 */
class SpecificHostRateLimitInterceptor(
    private val host: HttpUrl,
    private val permits: Int = 1,
    private val period: Long = 1,
    private val unit: TimeUnit = TimeUnit.SECONDS,
) : Interceptor {
    
    private val requestQueue = ArrayList<Long>(permits)
    private val rateLimitMillis = unit.toMillis(period)
    
    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // Only apply rate limiting to requests matching the host
        if (!request.url.host.equals(host.host, ignoreCase = true)) {
            return chain.proceed(request)
        }
        
        // Clean up old requests
        val now = System.currentTimeMillis()
        requestQueue.removeAll { it < now - rateLimitMillis }
        
        // Wait if necessary
        if (requestQueue.size >= permits) {
            val oldestRequest = requestQueue.minOrNull() ?: now
            val waitTime = rateLimitMillis - (now - oldestRequest)
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime)
                } catch (e: InterruptedException) {
                    throw IOException("Rate limit wait interrupted", e)
                }
            }
            // Remove oldest request
            requestQueue.removeAll { it <= oldestRequest }
        }
        
        // Add current request
        requestQueue.add(System.currentTimeMillis())
        
        return chain.proceed(request)
    }
}

/**
 * Extension function to add specific host rate limiting to OkHttpClient.
 */
fun OkHttpClient.Builder.rateLimit(
    host: HttpUrl,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(SpecificHostRateLimitInterceptor(host, permits, period, unit))
}

/**
 * Extension function to add specific host rate limiting by hostname.
 */
fun OkHttpClient.Builder.rateLimit(
    hostname: String,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val url = HttpUrl.Builder()
        .scheme("https")
        .host(hostname)
        .build()
    return rateLimit(url, permits, period, unit)
}

/**
 * Alias for rateLimit(HttpUrl, ...) - used by some extensions.
 */
fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return rateLimit(url, permits, period, unit)
}

/**
 * Duration-based overload used by Aniyomi extension-lib 14+.
 */
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder {
    return addInterceptor(
        SpecificHostRateLimitInterceptor(
            host = httpUrl,
            permits = permits,
            period = period.inWholeMilliseconds,
            unit = TimeUnit.MILLISECONDS,
        ),
    )
}

/**
 * Alias for rateLimit(String, ...) - used by some extensions.
 */
fun OkHttpClient.Builder.rateLimitHost(
    hostname: String,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return rateLimit(hostname, permits, period, unit)
}

/**
 * Duration-based string overload used by Aniyomi extension-lib 14+.
 */
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder {
    return rateLimitHost(url.toHttpUrl(), permits, period)
}

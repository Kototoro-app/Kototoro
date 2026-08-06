package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A stubbed RateLimitInterceptor for Mihon extension compatibility.
 * In a real implementation, this would handle actual rate limiting.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(RateLimitInterceptor(permits, period, unit))
}

/**
 * Duration-based overload used by Aniyomi extension-lib 14+.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder {
    return addInterceptor(RateLimitInterceptor(permits, period.inWholeMilliseconds, TimeUnit.MILLISECONDS))
}

/**
 * Overload for extensions using milliseconds.
 */
fun OkHttpClient.Builder.rateLimitHost(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(RateLimitInterceptor(permits, period, unit))
}

class RateLimitInterceptor(
    private val permits: Int,
    private val period: Long,
    private val unit: TimeUnit,
) : Interceptor {
    
    // Minimal implementation: just pass through or a simple delay
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}

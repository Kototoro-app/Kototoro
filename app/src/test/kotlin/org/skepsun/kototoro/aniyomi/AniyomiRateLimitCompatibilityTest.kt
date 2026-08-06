package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AniyomiRateLimitCompatibilityTest {

    @Test
    fun `extension lib 14 duration symbols remain available`() {
        val rateLimitMethods = Class.forName(
            "eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt",
        ).declaredMethods
        val hostRateLimitMethods = Class.forName(
            "eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptorKt",
        ).declaredMethods

        assertTrue(rateLimitMethods.any { method ->
            method.name == "rateLimit-SxA4cEA\$default" && method.parameterCount == 5
        })
        assertTrue(hostRateLimitMethods.any { method ->
            method.name == "rateLimitHost-Wn2Vu4Y" && method.parameterTypes[1] == HttpUrl::class.java
        })
    }

    @Test
    fun `duration overloads install rate limit interceptors`() {
        val client = OkHttpClient.Builder()
            .rateLimit(permits = 3, period = 1.seconds)
            .rateLimitHost(
                httpUrl = "https://status.miruro.com/".toHttpUrl(),
                permits = 1,
                period = 2.seconds,
            )
            .build()

        assertEquals(2, client.interceptors.size)
    }
}

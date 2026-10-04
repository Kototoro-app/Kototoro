package org.skepsun.kototoro.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

internal fun createKtorOkHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    // Preserve OkHttp redirect methods and cross-origin authorization stripping.
    followRedirects = false
    engine {
        preconfigured = okHttpClient
        config {
            followRedirects(okHttpClient.followRedirects)
            followSslRedirects(okHttpClient.followSslRedirects)
            retryOnConnectionFailure(okHttpClient.retryOnConnectionFailure)
        }
    }
}

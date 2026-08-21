package org.skepsun.kototoro.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.util.ext.mangaSourceKey
import org.skepsun.kototoro.core.model.isLocal

class ContentSourceHeaderInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val mangaSource = chain.request.extras[mangaSourceKey]?.unwrap()
        if (mangaSource == null || mangaSource.isLocal == true || mangaSource is org.skepsun.kototoro.core.parser.external.ExternalContentSource) {
            return chain.proceed()
        }

        val request = chain.request
        val newHeaders = request.httpHeaders.newBuilder()
            .set(CommonHeaders.MANGA_SOURCE, mangaSource.name)
            .build()
        val newRequest = request.newBuilder()
            .httpHeaders(newHeaders)
            .build()
        return chain.withRequest(newRequest).proceed()
    }
}

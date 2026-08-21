package org.skepsun.kototoro.core.parser.tvbox

import android.content.Context
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy

internal object TVBoxSpiderRuntimeFactory {

    fun create(
        source: JsonContentSource,
        config: TVBoxStoredConfig,
        context: Context,
        httpClient: LegadoHttpClient,
        videoLocalCacheProxy: VideoLocalCacheProxy,
    ): TVBoxSpiderRuntime? {
        return when {
            config.site.type == 4 -> TVBoxQuickJsSpiderRuntime(
                source = source,
                config = config,
                context = context,
                httpClient = httpClient,
                videoLocalCacheProxy = videoLocalCacheProxy,
            )

            config.site.type == 3 || config.site.api.startsWith("csp_", ignoreCase = true) -> TVBoxJarSpiderRuntime(
                source = source,
                config = config,
                context = context,
                httpClient = httpClient,
                videoLocalCacheProxy = videoLocalCacheProxy,
            )

            else -> null
        }
    }
}

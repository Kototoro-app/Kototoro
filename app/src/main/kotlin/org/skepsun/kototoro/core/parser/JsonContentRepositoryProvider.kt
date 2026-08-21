package org.skepsun.kototoro.core.parser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.javascript.BrowserLauncher
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar
import org.skepsun.kototoro.core.parser.legado.LegadoRepository
import org.skepsun.kototoro.core.parser.tvbox.TVBoxRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy
import javax.inject.Inject

class JsonContentRepositoryProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentCache: MemoryContentCache,
    private val legadoHttpClient: LegadoHttpClient,
    private val jsEngine: JavaScriptEngine,
    private val loaderContext: ContentLoaderContextImpl,
    private val videoLocalCacheProxy: VideoLocalCacheProxy,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = source is JsonContentSource

    override fun create(source: ContentSource): ContentRepository? {
        if (source !is JsonContentSource) return null
        return when (source.entity.type) {
            JsonSourceType.LEGADO -> {
                val browserLauncher = BrowserLauncher(
                    context = context,
                    cookieJar = PersistentCookieJar(legadoHttpClient.getCookieJar()),
                )
                val legadoPrefs = context.getSharedPreferences("legado_source_store", Context.MODE_PRIVATE)
                LegadoRepository(
                    source = source,
                    httpClient = legadoHttpClient,
                    jsEngine = jsEngine,
                    memoryCache = contentCache,
                    browserLauncher = browserLauncher,
                    legadoPrefs = legadoPrefs,
                )
            }
            JsonSourceType.TVBOX -> TVBoxRepository(
                source = source,
                context = context,
                httpClient = legadoHttpClient,
                videoLocalCacheProxy = videoLocalCacheProxy,
            )
            JsonSourceType.JS -> JsContentRepository(source, loaderContext)
            JsonSourceType.LNREADER -> org.skepsun.kototoro.core.lnreader.LNReaderContentRepository(
                source = source,
                appContext = context,
                httpClient = loaderContext.httpClient,
            )
        }
    }
}

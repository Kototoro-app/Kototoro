package org.skepsun.kototoro.core.cache

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import org.skepsun.kototoro.core.util.ext.isLowRamDevice
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryContentCache @Inject constructor(application: Application) : ComponentCallbacks2 {

    private val isLowRam = application.isLowRamDevice()

    private val detailsCache = ExpiringLruCache<SafeDeferred<Content>>(if (isLowRam) 1 else 4, 5, TimeUnit.MINUTES)
    private val pagesCache =
        ExpiringLruCache<SafeDeferred<List<ContentPage>>>(if (isLowRam) 1 else 4, 10, TimeUnit.MINUTES)
    private val relatedContentCache =
        ExpiringLruCache<SafeDeferred<List<Content>>>(if (isLowRam) 1 else 3, 10, TimeUnit.MINUTES)

    init {
        application.registerComponentCallbacks(this)
    }

    suspend fun getDetails(source: ContentSource, url: String): Content? {
        return detailsCache[Key(source, url)]?.awaitOrNull()
    }

    fun putDetails(source: ContentSource, url: String, details: SafeDeferred<Content>) {
        detailsCache[Key(source, url)] = details
    }

    suspend fun getPages(source: ContentSource, url: String): List<ContentPage>? {
        return pagesCache[Key(source, url)]?.awaitOrNull()
    }

    fun putPages(source: ContentSource, url: String, pages: SafeDeferred<List<ContentPage>>) {
        pagesCache[Key(source, url)] = pages
    }

    suspend fun getRelatedContent(source: ContentSource, url: String): List<Content>? {
        return relatedContentCache[Key(source, url)]?.awaitOrNull()
    }

    fun putRelatedContent(source: ContentSource, url: String, related: SafeDeferred<List<Content>>) {
        relatedContentCache[Key(source, url)] = related
    }

    fun clear(source: ContentSource) {
        clearCache(detailsCache, source)
        clearCache(pagesCache, source)
        clearCache(relatedContentCache, source)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() = Unit

    override fun onTrimMemory(level: Int) {
        trimCache(detailsCache, level)
        trimCache(pagesCache, level)
        trimCache(relatedContentCache, level)
    }

    private fun trimCache(cache: ExpiringLruCache<*>, level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.clear()

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimToSize(1)

            else -> cache.trimToSize(cache.maxSize / 2)
        }
    }

    private fun clearCache(cache: ExpiringLruCache<*>, source: ContentSource) {
        cache.removeAll(source)
    }

    data class Key(
        val source: ContentSource,
        val url: String,
    )
}

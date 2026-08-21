package org.skepsun.kototoro.core.parser

import android.util.Log
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import javax.inject.Inject

class ParserContentRepositoryProvider @Inject constructor(
    private val loaderContext: ContentLoaderContext,
    private val contentCache: MemoryContentCache,
    private val mirrorSwitcher: MirrorSwitcher,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = GlobalExtensionManager.contentSources.value.any { it.name == source.name }

    override fun create(source: ContentSource): ContentRepository? {
        return try {
            val parser = GlobalExtensionManager.getContentParser(source, loaderContext)
            ParserContentRepository(
                parser = parser,
                cache = contentCache,
                mirrorSwitcher = mirrorSwitcher,
            )
        } catch (e: Throwable) {
            Log.e("ParserContentRepoProvider", "Failed to create parser for ${source.name}: ${e.message}. " +
                "The installed parser JAR may be incompatible with this app version.", e)
            null
        }
    }
}

package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserRepository
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuLoaderContextAdapter
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class KotatsuContentRepositoryProvider @Inject constructor(
    private val loaderContext: ContentLoaderContext,
    private val contentCache: MemoryContentCache,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = source is KotatsuParserSource
    override fun create(source: ContentSource): ContentRepository? {
        if (source !is KotatsuParserSource) return null
        val mangaContext = KotatsuLoaderContextAdapter(loaderContext)
        return KotatsuParserRepository(
            parser = GlobalExtensionManager.getMangaParser(source.delegate, mangaContext),
            kotatsuSource = source,
            loaderContext = loaderContext,
            cache = contentCache,
        )
    }
}

package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class AniyomiContentRepositoryProvider @Inject constructor(
    private val contentCache: MemoryContentCache,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = source is AniyomiAnimeSource

    override fun create(source: ContentSource): ContentRepository? {
        if (source !is AniyomiAnimeSource) return null
        return AniyomiAnimeRepository(
            source = source,
            cache = contentCache,
        )
    }
}

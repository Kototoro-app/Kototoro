package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.ireader.IReaderMangaRepository
import org.skepsun.kototoro.ireader.model.IReaderMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class IReaderContentRepositoryProvider @Inject constructor(
    private val contentCache: MemoryContentCache,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = source is IReaderMangaSource

    override fun create(source: ContentSource): ContentRepository? {
        android.util.Log.d("IReaderProvider", "create() called with source: ${source.name}, type: ${source::class.simpleName}")
        if (source !is IReaderMangaSource) {
            android.util.Log.d("IReaderProvider", "Source is not IReaderMangaSource, returning null")
            return null
        }
        android.util.Log.d("IReaderProvider", "Creating IReaderMangaRepository for source: ${source.name}")
        return IReaderMangaRepository(
            source = source,
            cache = contentCache,
        )
    }
}

package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class TestContentRepositoryProvider @Inject constructor(
    private val loaderContext: ContentLoaderContext,
    private val contentCache: MemoryContentCache,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = source == TestContentSource

    override fun create(source: ContentSource): ContentRepository? {
        if (source != TestContentSource) return null
        return TestContentRepository(
            loaderContext = loaderContext,
            cache = contentCache,
        )
    }
}

package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.extensions.recovery.SourceRefreshReporter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tsundoku.TsundokuNovelRepository
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource
import javax.inject.Inject

/**
 * Provider of [TsundokuNovelRepository] for Tsundoku novel sources (T3B.1).
 */
class TsundokuContentRepositoryProvider @Inject constructor(
    private val contentCache: MemoryContentCache,
    private val refreshReporter: SourceRefreshReporter,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean = source is TsundokuNovelSource

    override fun create(source: ContentSource): ContentRepository? {
        if (source !is TsundokuNovelSource) {
            return null
        }
        return TsundokuNovelRepository(
            source = source,
            cache = contentCache,
            refreshReporter = refreshReporter,
        )
    }
}

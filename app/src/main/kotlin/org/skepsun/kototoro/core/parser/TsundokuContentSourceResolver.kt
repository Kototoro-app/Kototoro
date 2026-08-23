package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

/**
 * Rebuilds a live [TsundokuNovelSource] from an anonymous name-only wrapper such as the one
 * `ContentListActivity` reconstructs from `KEY_SOURCE` (`TSUNDOKU_{sourceId}`). Mirrors the
 * other Tachiyomi-ABI resolvers so Tsundoku novel sources can be browsed (T3B).
 */
class TsundokuContentSourceResolver @Inject constructor(
    private val tsundokuExtensionManager: TsundokuExtensionManager,
) : ContentSourceResolver {

    override fun supports(source: ContentSource): Boolean {
        return source !is TsundokuNovelSource && source != UnknownContentSource && (
            source.name.startsWith(TSUNDOKU_PREFIX) ||
                findByDisplayName(source.name) != null
            )
    }

    override fun resolve(source: ContentSource): ContentSource? {
        if (!supports(source)) {
            return null
        }
        android.util.Log.d("TsundokuResolver", "Resolving source: ${source.name}")
        val resolved = if (source.name.startsWith(TSUNDOKU_PREFIX)) {
            tsundokuExtensionManager.resolveSource(source.name)
        } else {
            findByDisplayName(source.name)
        }
        android.util.Log.d("TsundokuResolver", "Resolved result: $resolved")
        return resolved
    }

    private fun findByDisplayName(name: String): TsundokuNovelSource? {
        return tsundokuExtensionManager.getTsundokuNovelSources()
            .singleOrNull { source -> source.displayName == name }
    }

    private companion object {
        private const val TSUNDOKU_PREFIX = "TSUNDOKU_"
    }
}

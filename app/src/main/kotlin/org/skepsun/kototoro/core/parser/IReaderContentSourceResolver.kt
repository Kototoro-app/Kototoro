package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.ireader.model.IReaderMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class IReaderContentSourceResolver @Inject constructor(
    private val ireaderExtensionManager: IReaderExtensionManager,
) : ContentSourceResolver {

    override fun supports(source: ContentSource): Boolean {
        return source !is IReaderMangaSource && source.name.startsWith(IREADER_PREFIX)
    }

    override fun resolve(source: ContentSource): ContentSource? {
        if (!supports(source)) {
            return null
        }
        android.util.Log.d("IReaderResolver", "Resolving source: ${source.name}")
        val resolved = ireaderExtensionManager.getIReaderMangaSourceByName(source.name)
        android.util.Log.d("IReaderResolver", "Resolved result: $resolved")
        return resolved
    }

    private companion object {
        private const val IREADER_PREFIX = "IREADER_"
    }
}

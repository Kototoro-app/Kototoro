package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class AniyomiContentSourceResolver @Inject constructor(
    private val aniyomiExtensionManager: AniyomiExtensionManager,
) : ContentSourceResolver {

    override fun supports(source: ContentSource): Boolean {
        return source !is AniyomiAnimeSource && source.name.startsWith(ANIYOMI_PREFIX)
    }

    override fun resolve(source: ContentSource): ContentSource? {
        if (!supports(source)) {
            return null
        }
        android.util.Log.d("AniyomiResolver", "Resolving source: ${source.name}")
        val resolved = aniyomiExtensionManager.getAniyomiAnimeSourceByName(source.name)
        android.util.Log.d("AniyomiResolver", "Resolved result: $resolved")
        return resolved
    }

    private companion object {
        private const val ANIYOMI_PREFIX = "ANIYOMI_"
    }
}

package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class MihonContentSourceResolver @Inject constructor(
	private val mihonExtensionManager: MihonExtensionManager,
) : ContentSourceResolver {

	override fun supports(source: ContentSource): Boolean {
		return source !is MihonMangaSource && source != UnknownContentSource && (
			source.name.startsWith(MIHON_PREFIX) ||
				findByDisplayName(source.name) != null
			)
	}

	override fun resolve(source: ContentSource): ContentSource? {
		if (!supports(source)) {
			return null
		}
		android.util.Log.d("MihonResolver", "Resolving source: ${source.name}")
		val resolved = if (source.name.startsWith(MIHON_PREFIX)) {
			mihonExtensionManager.getMihonMangaSourceByName(source.name)
		} else {
			findByDisplayName(source.name)
		}
		android.util.Log.d("MihonResolver", "Resolved result: $resolved")
		return resolved
	}

	private fun findByDisplayName(name: String): MihonMangaSource? {
		return mihonExtensionManager.getMihonMangaSources()
			.singleOrNull { source -> source.displayName == name }
	}

	private companion object {
		private const val MIHON_PREFIX = "MIHON_"
	}
}

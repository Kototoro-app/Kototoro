package org.skepsun.kototoro.reader.ui.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.suspendlazy.getOrNull
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy

data class ImageServerOptions(
	val selectedValue: String?,
	val entries: List<ImageServerEntry>,
)

data class ImageServerEntry(
	val value: String?,
	val label: String?,
)

class ImageServerDelegate(
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val mangaSource: ContentSource?,
) {

	private val repositoryLazy = suspendLazy {
		mangaRepositoryFactory.create(checkNotNull(mangaSource)) as ParserContentRepository
	}

	suspend fun loadOptions(): ImageServerOptions? = withContext(Dispatchers.Default) {
		val repository = repositoryLazy.getOrNull() ?: return@withContext null
		val key = repository.getConfigKeys().firstNotNullOfOrNull {
			it as? ConfigKey.PreferredImageServer
		} ?: return@withContext null
		val config = repository.getConfig()
		ImageServerOptions(
			selectedValue = config[key],
			entries = key.presetValues.map { (value, label) -> ImageServerEntry(value, label) },
		)
	}

	suspend fun select(value: String?): Boolean = withContext(Dispatchers.Default) {
		val repository = repositoryLazy.getOrNull() ?: return@withContext false
		val key = repository.getConfigKeys().firstNotNullOfOrNull {
			it as? ConfigKey.PreferredImageServer
		} ?: return@withContext false
		val config = repository.getConfig()
		if (config[key] == value) return@withContext false
		config[key] = value
			repository.invalidateCache()
		true
	}
}

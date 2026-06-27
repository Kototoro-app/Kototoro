package org.skepsun.kototoro.core.parser

import androidx.collection.ArrayMap
import org.skepsun.kototoro.parsers.model.ContentSource
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepositoryInstanceCache @Inject constructor() {

	data class LookupResult(
		val repository: ContentRepository,
		val cacheHit: Boolean,
	)

	private val cache = ArrayMap<ContentSource, WeakReference<ContentRepository>>()

	fun get(source: ContentSource): ContentRepository? {
		return synchronized(cache) {
			cache[source]?.get()
		}
	}

	fun getOrPut(source: ContentSource, create: () -> ContentRepository): ContentRepository {
		get(source)?.let { return it }
		return synchronized(cache) {
			cache[source]?.get()?.let { return it }
			create().also { repository ->
				cache[source] = WeakReference(repository)
			}
		}
	}

	fun getOrPutWithResult(source: ContentSource, create: () -> ContentRepository): LookupResult {
		return getOrPutWithResult(source, shouldCache = { true }, create = create)
	}

	fun getOrPutWithResult(
		source: ContentSource,
		shouldCache: (ContentRepository) -> Boolean,
		create: () -> ContentRepository,
	): LookupResult {
		get(source)?.let { return LookupResult(repository = it, cacheHit = true) }
		return synchronized(cache) {
			cache[source]?.get()?.let { return LookupResult(repository = it, cacheHit = true) }
			val repository = create()
			if (shouldCache(repository)) {
				cache[source] = WeakReference(repository)
			}
			LookupResult(repository = repository, cacheHit = false)
		}
	}

	fun clear() {
		synchronized(cache) {
			cache.clear()
		}
	}
}

package org.skepsun.kototoro.explore.data

import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceAvailabilityRepository @Inject constructor(
	private val settings: AppSettings,
) {

	fun observeAvailability(): Flow<Map<String, ContentSourceAvailability>> {
		return settings.observe(KEY_AVAILABLE_SOURCES, KEY_EMPTY_SOURCES)
			.map { buildAvailabilityMap() }
	}

	fun getAvailability(source: ContentSource): ContentSourceAvailability {
		val name = source.name
		return when {
			name in getSet(KEY_EMPTY_SOURCES) -> ContentSourceAvailability.EMPTY
			name in getSet(KEY_AVAILABLE_SOURCES) -> ContentSourceAvailability.AVAILABLE
			else -> ContentSourceAvailability.UNKNOWN
		}
	}

	fun observeAvailability(source: ContentSource): Flow<ContentSourceAvailability> {
		return observeAvailability().map { it[source.name] ?: ContentSourceAvailability.UNKNOWN }
	}

	fun markAvailable(source: ContentSource) {
		setAvailability(source.name, ContentSourceAvailability.AVAILABLE)
	}

	fun markEmpty(source: ContentSource) {
		setAvailability(source.name, ContentSourceAvailability.EMPTY)
	}

	fun setAvailability(source: ContentSource, availability: ContentSourceAvailability) {
		setAvailability(source.name, availability)
	}

	private fun setAvailability(sourceName: String, availability: ContentSourceAvailability) {
		val available = getSet(KEY_AVAILABLE_SOURCES).toMutableSet()
		val empty = getSet(KEY_EMPTY_SOURCES).toMutableSet()
		available.remove(sourceName)
		empty.remove(sourceName)
		when (availability) {
			ContentSourceAvailability.AVAILABLE -> available += sourceName
			ContentSourceAvailability.EMPTY -> empty += sourceName
			ContentSourceAvailability.UNKNOWN -> Unit
		}
		settings.prefs.edit {
			putStringSet(KEY_AVAILABLE_SOURCES, available)
			putStringSet(KEY_EMPTY_SOURCES, empty)
		}
	}

	private fun buildAvailabilityMap(): Map<String, ContentSourceAvailability> {
		val empty = getSet(KEY_EMPTY_SOURCES)
		val available = getSet(KEY_AVAILABLE_SOURCES)
		return buildMap(empty.size + available.size) {
			available.forEach { put(it, ContentSourceAvailability.AVAILABLE) }
			empty.forEach { put(it, ContentSourceAvailability.EMPTY) }
		}
	}

	private fun getSet(key: String): Set<String> {
		return settings.prefs.getStringSet(key, emptySet()).orEmpty()
	}

	private companion object {
		const val KEY_AVAILABLE_SOURCES = "available_sources"
		const val KEY_EMPTY_SOURCES = "empty_sources"
	}
}

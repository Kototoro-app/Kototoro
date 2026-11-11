package org.skepsun.kototoro.explore.data

import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single mapping from a failed availability probe to a stored state: a Cloudflare exception
 * anywhere in the cause chain is CLOUDFLARE, every other failure is EMPTY.
 */
fun classifyAvailability(error: Throwable): ContentSourceAvailability =
    if (error.findCloudFlareException() != null) {
        ContentSourceAvailability.CLOUDFLARE
    } else {
        ContentSourceAvailability.EMPTY
    }

@Singleton
class SourceAvailabilityRepository @Inject constructor(
    private val settings: AppSettings,
) {

    fun observeAvailability(): Flow<Map<String, ContentSourceAvailability>> {
        return settings.observe(KEY_AVAILABLE_SOURCES, KEY_EMPTY_SOURCES, KEY_CLOUDFLARE_SOURCES)
            .map { buildAvailabilityMap() }
    }

    fun getAvailability(source: ContentSource): ContentSourceAvailability {
        return resolveAvailability(
            name = source.name,
            empty = getSet(KEY_EMPTY_SOURCES),
            cloudflare = getSet(KEY_CLOUDFLARE_SOURCES),
            available = getSet(KEY_AVAILABLE_SOURCES),
        )
    }

    /** Forgets a stored CLOUDFLARE verdict (the challenge was solved); EMPTY / AVAILABLE verdicts are left alone. */
    @Synchronized
    fun clearCloudflare(source: ContentSource) {
        val cloudflare = getSet(KEY_CLOUDFLARE_SOURCES)
        if (source.name in cloudflare) {
            settings.prefs.edit { putStringSet(KEY_CLOUDFLARE_SOURCES, cloudflare - source.name) }
        }
    }

    /** Forgets every stored verdict for the named sources (extension updated, reinstalled or removed). */
    fun clearAvailability(sourceNames: Collection<String>) {
        setAvailabilities(sourceNames.associateWith { ContentSourceAvailability.UNKNOWN })
    }

    fun observeAvailability(source: ContentSource): Flow<ContentSourceAvailability> {
        return observeAvailability().map { it[source.name] ?: ContentSourceAvailability.UNKNOWN }
    }

    fun markAvailable(source: ContentSource) {
        // No prefs write when the verdict is already AVAILABLE (called on every successful page).
        if (getAvailability(source) == ContentSourceAvailability.AVAILABLE) return
        setAvailability(source.name, ContentSourceAvailability.AVAILABLE)
    }

    fun markEmpty(source: ContentSource) {
        setAvailability(source.name, ContentSourceAvailability.EMPTY)
    }

    fun setAvailability(source: ContentSource, availability: ContentSourceAvailability) {
        setAvailability(source.name, availability)
    }

    private fun setAvailability(sourceName: String, availability: ContentSourceAvailability) {
        setAvailabilities(mapOf(sourceName to availability))
    }

    /** Applies all [updates] with a single read-modify-write and a single prefs commit. */
    @Synchronized
    fun setAvailabilities(updates: Map<String, ContentSourceAvailability>) {
        if (updates.isEmpty()) return
        val available = getSet(KEY_AVAILABLE_SOURCES).toMutableSet()
        val empty = getSet(KEY_EMPTY_SOURCES).toMutableSet()
        val cloudflare = getSet(KEY_CLOUDFLARE_SOURCES).toMutableSet()
        for ((sourceName, availability) in updates) {
            available.remove(sourceName)
            empty.remove(sourceName)
            cloudflare.remove(sourceName)
            when (availability) {
                ContentSourceAvailability.AVAILABLE -> available += sourceName
                ContentSourceAvailability.EMPTY -> empty += sourceName
                ContentSourceAvailability.CLOUDFLARE -> cloudflare += sourceName
                ContentSourceAvailability.UNKNOWN -> Unit
            }
        }
        settings.prefs.edit {
            putStringSet(KEY_AVAILABLE_SOURCES, available)
            putStringSet(KEY_EMPTY_SOURCES, empty)
            putStringSet(KEY_CLOUDFLARE_SOURCES, cloudflare)
        }
    }

    private fun buildAvailabilityMap(): Map<String, ContentSourceAvailability> {
        val empty = getSet(KEY_EMPTY_SOURCES)
        val available = getSet(KEY_AVAILABLE_SOURCES)
        val cloudflare = getSet(KEY_CLOUDFLARE_SOURCES)
        return buildMap(empty.size + available.size + cloudflare.size) {
            for (name in available + cloudflare + empty) {
                put(name, resolveAvailability(name, empty, cloudflare, available))
            }
        }
    }

    /** The one precedence rule for a name that sits in several sets: EMPTY, then CLOUDFLARE, then AVAILABLE. */
    private fun resolveAvailability(
        name: String,
        empty: Set<String>,
        cloudflare: Set<String>,
        available: Set<String>,
    ): ContentSourceAvailability = when {
        name in empty -> ContentSourceAvailability.EMPTY
        name in cloudflare -> ContentSourceAvailability.CLOUDFLARE
        name in available -> ContentSourceAvailability.AVAILABLE
        else -> ContentSourceAvailability.UNKNOWN
    }

    private fun getSet(key: String): Set<String> {
        return settings.prefs.getStringSet(key, emptySet()).orEmpty()
    }

    private companion object {
        const val KEY_AVAILABLE_SOURCES = AppSettings.KEY_AVAILABLE_SOURCES
        const val KEY_EMPTY_SOURCES = AppSettings.KEY_EMPTY_SOURCES
        const val KEY_CLOUDFLARE_SOURCES = AppSettings.KEY_CLOUDFLARE_SOURCES
    }
}

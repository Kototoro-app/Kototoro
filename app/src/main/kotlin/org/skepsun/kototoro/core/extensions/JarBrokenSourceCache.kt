package org.skepsun.kototoro.core.extensions

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/**
 * Remembers which sources a plugin JAR declares as `@Broken`, keyed by the identity of the file
 * (name + size + last-modified).
 *
 * Resolving that set means enumerating the whole DEX and loading every class to read its annotations.
 * For big parser bundles that is the single most expensive step of `GlobalExtensionManager.initialize()`,
 * and its result only changes when the JAR file changes, so it is persisted instead of recomputed on
 * every cold start.
 */
internal class JarBrokenSourceCache(private val prefs: SharedPreferences) {

	/** Cached broken source names, or `null` when this file has never been scanned. */
	fun read(jarFile: File): Set<String>? {
		val stored = prefs.getString(key(jarFile), null) ?: return null
		return stored.split(SEPARATOR).filterTo(LinkedHashSet()) { it.isNotEmpty() }
	}

	fun write(jarFile: File, brokenSourceNames: Set<String>) {
		// commit() instead of apply(): the scan runs once per JAR anyway, and a pending async write is
		// lost when the process is killed, which would force a full rescan of every JAR next start.
		prefs.edit(commit = true) { putString(key(jarFile), brokenSourceNames.joinToString(SEPARATOR)) }
	}

	/** Drops entries of JARs that are no longer present, so the cache cannot grow forever. */
	fun retainOnly(jarFiles: List<File>) {
		val liveKeys = jarFiles.mapTo(HashSet()) { key(it) }
		val staleKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it !in liveKeys }
		if (staleKeys.isEmpty()) return
		prefs.edit(commit = true) { staleKeys.forEach(::remove) }
	}

	private fun key(jarFile: File): String = buildString {
		append(KEY_PREFIX)
		append(jarFile.name)
		append(FIELD_SEPARATOR)
		append(jarFile.length())
		append(FIELD_SEPARATOR)
		append(jarFile.lastModified())
	}

	companion object {

		/**
		 * Bump when the annotation scan changes, so results produced by an older scan are ignored
		 * instead of pinning stale broken flags.
		 */
		private const val SCHEMA = 1
		private const val PREFS_NAME = "jar_broken_sources"
		private const val KEY_PREFIX = "v$SCHEMA:"
		private const val FIELD_SEPARATOR = '|'
		private const val SEPARATOR = "\n"

		fun get(context: Context): JarBrokenSourceCache = JarBrokenSourceCache(
			context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
		)
	}
}

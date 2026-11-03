package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import androidx.core.content.edit

/**
 * Extension packages the user removed on this device, by sync kind and package id.
 *
 * Sync snapshots only list packages that exist, so a removed package still present in the remote
 * snapshot (or on another device) used to be restored on the next sync. The removal time is uploaded as
 * a tombstone and wins over every copy installed no later than it.
 */
object ExtensionRemovalLog {

    private const val PREFS_NAME = "sync_extension_removals"
    // Prefs are stored as XML, which cannot hold control characters; package ids never contain a slash.
    private const val SEPARATOR = '/'

    fun record(context: Context, kind: String, packageId: String, removedAt: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(key(kind, packageId), removedAt) }
    }

    fun forget(context: Context, kind: String, packageId: String) {
        prefs(context).edit { remove(key(kind, packageId)) }
    }

    /** Removals as `(kind, packageId) -> removedAt`. */
    fun entries(context: Context): Map<Pair<String, String>, Long> {
        return prefs(context).all.mapNotNull { (key, value) ->
            val removedAt = value as? Long ?: return@mapNotNull null
            val kind = key.substringBefore(SEPARATOR, missingDelimiterValue = "")
            val packageId = key.substringAfter(SEPARATOR, missingDelimiterValue = "")
            if (kind.isEmpty() || packageId.isEmpty()) null else (kind to packageId) to removedAt
        }.toMap()
    }

    private fun key(kind: String, packageId: String) = "${kind.lowercase()}$SEPARATOR$packageId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

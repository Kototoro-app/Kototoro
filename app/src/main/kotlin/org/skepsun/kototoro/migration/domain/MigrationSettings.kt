package org.skepsun.kototoro.migration.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.model.ContentTypeFamily
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("migration", Context.MODE_PRIVATE)

    /** Ordered target source names for a content family, or null when never chosen. */
    fun getTargetSourceNames(family: ContentTypeFamily): List<String>? =
        prefs.getString(KEY_SOURCES_PREFIX + family.name, null)?.split('\n')?.filter { it.isNotEmpty() }

    fun setTargetSourceNames(family: ContentTypeFamily, names: List<String>) =
        prefs.edit { putString(KEY_SOURCES_PREFIX + family.name, names.joinToString("\n")) }

    var dataFlags: Set<MigrationDataFlag>
        get() = MigrationDataFlag.fromBits(prefs.getInt(KEY_FLAGS, MigrationDataFlag.toBits(MigrationDataFlag.ALL)))
        set(value) = prefs.edit { putInt(KEY_FLAGS, MigrationDataFlag.toBits(value)) }

    var matchMode: MatchMode
        get() = prefs.getString(KEY_MATCH_MODE, null)
            ?.let { name -> MatchMode.entries.firstOrNull { it.name == name } } ?: MatchMode.FIRST_HIT
        set(value) = prefs.edit { putString(KEY_MATCH_MODE, value.name) }

    var extraQuery: String
        get() = prefs.getString(KEY_EXTRA_QUERY, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_EXTRA_QUERY, value) }

    var isDeepSearch: Boolean
        get() = prefs.getBoolean(KEY_DEEP_SEARCH, false)
        set(value) = prefs.edit { putBoolean(KEY_DEEP_SEARCH, value) }

    var hideUnmatched: Boolean
        get() = prefs.getBoolean(KEY_HIDE_UNMATCHED, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_UNMATCHED, value) }

    var hideWithoutUpdates: Boolean
        get() = prefs.getBoolean(KEY_HIDE_NO_UPDATES, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_NO_UPDATES, value) }

    var isDuplicateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_DUPLICATE_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_DUPLICATE_CHECK, value) }

    /** Key of the unhealthy-source set the user dismissed on the favourites banner. */
    var dismissedHealthKey: String?
        get() = prefs.getString(KEY_DISMISSED_HEALTH, null)
        set(value) = prefs.edit { putString(KEY_DISMISSED_HEALTH, value) }

    private companion object {
        const val KEY_SOURCES_PREFIX = "sources_"
        const val KEY_FLAGS = "flags"
        const val KEY_MATCH_MODE = "match_mode"
        const val KEY_EXTRA_QUERY = "extra_query"
        const val KEY_DEEP_SEARCH = "deep_search"
        const val KEY_HIDE_UNMATCHED = "hide_unmatched"
        const val KEY_HIDE_NO_UPDATES = "hide_no_updates"
        const val KEY_DUPLICATE_CHECK = "duplicate_check"
        const val KEY_DISMISSED_HEALTH = "dismissed_health"
    }
}

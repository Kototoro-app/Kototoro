package eu.kanade.tachiyomi.animesource

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A source that has a configuration screen. Its settings live in the host Application's `source_<id>` store, as in
 * Aniyomi (and as the manga ABI keeps them).
 */
interface ConfigurableAnimeSource : AnimeSource {
    /** The store the source reads its settings from. */
    fun getSourcePreferences(): SharedPreferences = sourcePreferences(preferenceKey())

    /**
     * Set up the preference screen for this source.
     * @param screen The preference screen to add preferences to.
     */
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableAnimeSource.preferenceKey(): String = "source_$id"

fun ConfigurableAnimeSource.sourcePreferences(): SharedPreferences = sourcePreferences(preferenceKey())

fun sourcePreferences(key: String): SharedPreferences = Injekt.get<Application>().getSharedPreferences(key, 0)

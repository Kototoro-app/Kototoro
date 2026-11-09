package androidx.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * AndroidX's default preferences, which the Windows compatibility runtime lacks; plugins read their settings through it
 * when they load (Stremio, ViStream). The store is the one Cloudstream's `DataStore` uses on Windows, as on Android
 * both are the app's default preferences.
 */
object PreferenceManager {
	private const val DEFAULT_PREFERENCES = "cloudstream_default_preferences"

	@JvmStatic
	fun getDefaultSharedPreferences(context: Context): SharedPreferences =
		context.getSharedPreferences(DEFAULT_PREFERENCES, Context.MODE_PRIVATE)

	@JvmStatic
	fun getDefaultSharedPreferencesName(@Suppress("UNUSED_PARAMETER") context: Context): String = DEFAULT_PREFERENCES
}

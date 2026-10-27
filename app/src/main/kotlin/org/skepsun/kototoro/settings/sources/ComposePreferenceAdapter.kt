package org.skepsun.kototoro.settings.sources

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen

/**
 * Builds the androidx `PreferenceScreen` an extension fills in (`setupPreferenceScreen`) on top of the source's own
 * SharedPreferences file. Turning that screen into rows is [PreferenceScreenNodes]' job now, so both the Android page and
 * the Windows host draw the same protocol tree through `SourcePreferencesContent`.
 */
internal class ComposePreferenceAdapter(
    private val context: Context,
    private val sharedPreferencesName: String,
) {
    fun createScreen(): PreferenceScreen {
        val manager = PreferenceManager(context).apply {
            sharedPreferencesName = this@ComposePreferenceAdapter.sharedPreferencesName
        }
        return manager.createPreferenceScreen(context)
    }
}

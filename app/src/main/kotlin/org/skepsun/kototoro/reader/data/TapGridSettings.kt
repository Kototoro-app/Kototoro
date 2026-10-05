package org.skepsun.kototoro.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import org.skepsun.kototoro.core.util.ext.getEnumValue
import org.skepsun.kototoro.core.util.ext.observeChanges
import org.skepsun.kototoro.core.util.ext.putAll
import org.skepsun.kototoro.core.util.ext.putEnumValue
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.tapgrid.TapAction
import org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig
import javax.inject.Inject

@Reusable
class TapGridSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(KEY_INIT, false)) {
            initPrefs(withDefaultValues = true)
        }
    }

    fun getTapAction(area: TapGridArea, isLongTap: Boolean): TapAction? {
        val key = getPrefKey(area, isLongTap)
        return prefs.getEnumValue(key, TapAction::class.java)
    }

    fun setTapAction(area: TapGridArea, isLongTap: Boolean, action: TapAction?) {
        val key = getPrefKey(area, isLongTap)
        prefs.edit {
            if (action == null) {
                remove(key)
            } else {
                putEnumValue(key, action)
            }
        }
    }

    fun reset() {
        initPrefs(withDefaultValues = true)
    }

    fun disableAll() {
        initPrefs(withDefaultValues = false)
    }

    fun observeChanges() = prefs.observeChanges().flowOn(Dispatchers.IO)

    fun getAllValues(): Map<String, *> = prefs.all

    fun upsertAll(m: Map<String, *>) = prefs.edit {
        clear()
        putAll(m)
    }

    private fun initPrefs(withDefaultValues: Boolean) {
        prefs.edit {
            clear()
            if (withDefaultValues) {
                initDefaultActions(this)
            }
            putBoolean(KEY_INIT, true)
        }
    }

    private fun getPrefKey(area: TapGridArea, isLongTap: Boolean): String = TapGridConfig.prefKey(area, isLongTap)

    private fun initDefaultActions(editor: SharedPreferences.Editor) {
        for ((area, actions) in TapGridConfig.defaults) {
            actions.tapAction?.let { editor.putEnumValue(getPrefKey(area, false), it) }
            actions.longTapAction?.let { editor.putEnumValue(getPrefKey(area, true), it) }
        }
    }

    private companion object {

        private const val PREFS_NAME = "tap_grid"
        private const val KEY_INIT = TapGridConfig.KEY_INIT
    }
}

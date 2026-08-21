package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import org.skepsun.kototoro.core.util.ext.getEnumValue
import org.skepsun.kototoro.core.util.ext.putEnumValue
import org.skepsun.kototoro.core.util.ext.sanitizeHeaderValue
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.settings.utils.validation.DomainValidator
import java.io.File

class SourceSettings(context: Context, source: ContentSource) : ContentSourceConfig {

    private val prefs = context.getSharedPreferences(
        source.name.replace(File.separatorChar, '$'),
        Context.MODE_PRIVATE,
    )

    var defaultSortOrder: SortOrder?
        get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
        set(value) = prefs.edit { putEnumValue(KEY_SORT_ORDER, value) }

    val isSlowdownEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLOWDOWN, false)

    val isCaptchaNotificationsDisabled: Boolean
        get() = prefs.getBoolean(KEY_NO_CAPTCHA, false)

    val isCaptchaAutoResolveDisabled: Boolean
        get() = prefs.getBoolean(KEY_NO_AUTO_CAPTCHA, false)

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: ConfigKey<T>): T {
        return when (key) {
            is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
                .ifNullOrEmpty { key.defaultValue }
                .sanitizeHeaderValue()

            is ConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
                ?.trim()
                ?.takeIf { DomainValidator.isValidDomain(it) }
                ?: key.defaultValue

            is ConfigKey.Text -> prefs.getString(key.key, key.defaultValue)
                ?: key.defaultValue

            is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
            is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
            is ConfigKey.InterceptCloudflare -> prefs.getBoolean(key.key, key.defaultValue)
            is ConfigKey.Toggle -> prefs.getBoolean(key.key, key.defaultValue)
            is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
            is ConfigKey.PreferredLanguage -> prefs.getString(key.key, key.defaultValue)
                ?: key.defaultValue
        } as T
    }

    operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit {
        when (key) {
            is ConfigKey.Domain -> putString(key.key, value as String?)
            is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
            is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
            is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
            is ConfigKey.InterceptCloudflare -> putBoolean(key.key, value as Boolean)
            is ConfigKey.PreferredImageServer -> putString(key.key, value as String? ?: "")
            is ConfigKey.Text -> putString(key.key, value as String?)
            is ConfigKey.Toggle -> putBoolean(key.key, value as Boolean)
            is ConfigKey.PreferredLanguage -> putString(key.key, value as String?)
        }
    }

    fun subscribe(listener: OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unsubscribe(listener: OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {

        const val KEY_DOMAIN = "domain"
        const val KEY_NO_CAPTCHA = "no_captcha"
        const val KEY_NO_AUTO_CAPTCHA = "no_auto_captcha"
        const val KEY_SLOWDOWN = "slowdown"
        const val KEY_SORT_ORDER = "sort_order"
    }
}

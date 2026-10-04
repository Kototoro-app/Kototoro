package org.skepsun.kototoro.parserhost

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig

/** Same reading rules as the Android `SourceSettings`, over the portable preference store. */
internal class ParserSourceConfig(private val preferences: SourcePreferences) : ContentSourceConfig {

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: ConfigKey<T>): T {
        val snapshot = preferences.snapshot()
        fun text() = (snapshot[key.key] as? SourcePreferenceValue.Text)?.value
        fun toggle() = (snapshot[key.key] as? SourcePreferenceValue.Toggle)?.value
        return when (key) {
            is ConfigKey.UserAgent -> text()?.sanitizeHeaderValue()?.ifEmpty { null } ?: key.defaultValue
            is ConfigKey.Domain -> text()?.trim()?.takeIf(::isValidDomain) ?: key.defaultValue
            is ConfigKey.Text -> text() ?: key.defaultValue
            is ConfigKey.ShowSuspiciousContent -> toggle() ?: key.defaultValue
            is ConfigKey.SplitByTranslations -> toggle() ?: key.defaultValue
            is ConfigKey.InterceptCloudflare -> toggle() ?: key.defaultValue
            is ConfigKey.Toggle -> toggle() ?: key.defaultValue
            // An explicit empty value means "automatic" and must not fall back to the key's default server.
            is ConfigKey.PreferredImageServer ->
                if (key.key in snapshot) text()?.ifEmpty { null } else key.defaultValue?.ifEmpty { null }
            is ConfigKey.PreferredLanguage -> text() ?: key.defaultValue
        } as T
    }

    /** Persists [value] for [key]; the caller has already validated it against the key's declared type. */
    fun put(key: ConfigKey<*>, value: SourcePreferenceValue?): Boolean =
        preferences.edit(SourcePreferenceEdit(changes = mapOf(key.key to value)))

    companion object {
        internal fun isValidDomain(value: String): Boolean =
            value.isNotEmpty() && !value.contains('/') && !value.contains(' ') &&
                "https://$value/".toHttpUrlOrNull() != null

        internal fun String.sanitizeHeaderValue(): String =
            filter { it == '\t' || it in ' '..'~' }.trim()
    }
}

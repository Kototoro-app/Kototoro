package org.skepsun.kototoro.core.parser.kotatsu

import org.koitharu.kotatsu.parsers.config.ConfigKey as KTConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig as KTMangaSourceConfig
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig

internal class KotatsuConfigAdapter(
    private val delegate: ContentSourceConfig,
) : KTMangaSourceConfig {

    override fun <T> get(key: KTConfigKey<T>): T {
        val mappedKey = key.toKototoro()
        return if (mappedKey != null) {
            delegate[mappedKey]
        } else {
            key.defaultValue
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> KTConfigKey<T>.toKototoro(): ConfigKey<T>? = when (this) {
    is KTConfigKey.Domain -> ConfigKey.Domain(*presetValues) as ConfigKey<T>
    is KTConfigKey.ShowSuspiciousContent -> ConfigKey.ShowSuspiciousContent(defaultValue) as ConfigKey<T>
    is KTConfigKey.UserAgent -> ConfigKey.UserAgent(defaultValue) as ConfigKey<T>
    is KTConfigKey.SplitByTranslations -> ConfigKey.SplitByTranslations(defaultValue) as ConfigKey<T>
    is KTConfigKey.PreferredImageServer -> ConfigKey.PreferredImageServer(presetValues, defaultValue) as ConfigKey<T>
    is KTConfigKey.DisableUpdateChecking -> null
    is KTConfigKey.InterceptCloudflare -> ConfigKey.InterceptCloudflare(defaultValue) as ConfigKey<T>
    else -> null
}

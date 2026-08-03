package org.skepsun.kototoro.core.parser.tsuki

import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig

internal class TsukiConfigAdapter(
    private val delegate: ContentSourceConfig,
) : tsuki.config.MangaSourceConfig {

    override fun <T> get(key: tsuki.config.ConfigKey<T>): T {
        return delegate[key.toKototoro()]
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> tsuki.config.ConfigKey<T>.toKototoro(): ConfigKey<T> = when (this) {
    is tsuki.config.ConfigKey.Domain -> ConfigKey.Domain(*presetValues) as ConfigKey<T>
    is tsuki.config.ConfigKey.ShowSuspiciousContent -> ConfigKey.ShowSuspiciousContent(defaultValue) as ConfigKey<T>
    is tsuki.config.ConfigKey.UserAgent -> ConfigKey.UserAgent(defaultValue) as ConfigKey<T>
    is tsuki.config.ConfigKey.SplitByTranslations -> ConfigKey.SplitByTranslations(defaultValue) as ConfigKey<T>
    is tsuki.config.ConfigKey.PreferredImageServer -> ConfigKey.PreferredImageServer(presetValues, defaultValue) as ConfigKey<T>
}

package org.skepsun.kototoro.desktop.app

import java.util.Properties

/**
 * Android's string resources (Simplified Chinese, else the default locale), generated at build time from
 * `app/src/main/res` by `prepareDesktopStrings`, so shared screens show Android's exact text.
 */
internal object AndroidStrings {
    private val values: Properties by lazy {
        Properties().apply {
            AndroidStrings::class.java.getResourceAsStream("/android-strings.properties")
                ?.reader(Charsets.UTF_8)?.use(::load)
        }
    }

    /** The string named [name] (Android's `R.string.name`); the name itself when it is missing. */
    operator fun get(name: String): String = values.getProperty(name) ?: name

    /** The string array named [name] (Android's `R.array.name`), items resolved. */
    fun array(name: String): List<String> = values.getProperty("@array/$name")?.split('\u001F').orEmpty()
}

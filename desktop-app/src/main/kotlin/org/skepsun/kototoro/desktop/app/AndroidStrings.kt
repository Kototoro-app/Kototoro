package org.skepsun.kototoro.desktop.app

/**
 * Android's own string resources for the screens both hosts share, in the interface language (see [DesktopStrings]),
 * so the Windows UI shows Android's exact text instead of a second copy of it.
 *
 * The build exports them from `app/src/main/res` by `prepareDesktopStrings`: `android-strings.properties` is
 * Android's default (English) catalog and `android-strings_<tag>.properties` the translation of every offered
 * language, so a translation that misses a string still reads in English rather than showing a key.
 */
internal object AndroidStrings {
    private const val CATALOG = "android-strings"

    /** The string named [name] (Android's `R.string.name`); the name itself when it is missing. */
    operator fun get(name: String): String = DesktopStrings.android(CATALOG, name) ?: name

    /** The string named [name] with [args] substituted (Android's `getString(name, args)`), in the UI language. */
    fun format(name: String, vararg args: Any?): String = String.format(DesktopStrings.locale, get(name), *args)

    /** The string array named [name] (Android's `R.array.name`), items resolved. */
    fun array(name: String): List<String> = DesktopStrings.android(CATALOG, "@array/$name")?.split('\u001F').orEmpty()
}

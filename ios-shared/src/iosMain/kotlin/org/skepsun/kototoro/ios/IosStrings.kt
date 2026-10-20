package org.skepsun.kototoro.ios

import platform.Foundation.NSBundle

/**
 * Text from shared code that the app shows, looked up in the app's string catalog like Swift's `String(localized:)`:
 * the key is the Chinese source text, and `%@` (or `%1$@`) take [args] in order. Without a translation — or outside
 * the app, as in tests — the source text is used.
 */
internal fun localized(key: String, vararg args: Any?): String {
    val text = NSBundle.mainBundle.localizedStringForKey(key, value = key, table = null)
    if (args.isEmpty()) return text
    var next = 0
    return PLACEHOLDER.replace(text) { match ->
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
        args.getOrNull(index)?.toString().orEmpty()
    }
}

private val PLACEHOLDER = Regex("%(\\d+)\\$@|%@")

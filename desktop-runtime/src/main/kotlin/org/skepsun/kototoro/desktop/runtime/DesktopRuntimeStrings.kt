package org.skepsun.kototoro.desktop.runtime

/**
 * The text this module reports — validation failures, download errors and default names — as a catalog key plus an
 * English [fallback], with the host doing the actual translation.
 *
 * The catalogs live in `desktop-app` (`i18n/desktop-strings*.properties`), because they are the UI's, and this module
 * must not depend on it. So a message names its key and its English wording, and the desktop app installs [provider]
 * once; the `storage` CLI, the JSONL host and this module's tests keep the English fallback, which is exactly the
 * text a developer wants there. The key is why this replaced hardcoded Chinese: an untranslated message shows up as a
 * missing key in [desktop-app][org.skepsun.kototoro.desktop.app.DesktopStrings], not as one language leaking into
 * another.
 */
object DesktopRuntimeStrings {
    /** `(key, fallback, args) -> text`; null until the host installs its lookup. */
    @Volatile
    private var provider: ((String, String, Array<out Any?>) -> String)? = null

    /** Installs [lookup] (null restores the English fallbacks), typically once at start-up. */
    fun install(lookup: ((key: String, fallback: String, args: Array<out Any?>) -> String)?) {
        provider = lookup
    }

    /**
     * [key]'s text in the host's language, or [fallback] formatted with [args] when the host does not translate it or
     * is not present.
     */
    fun text(key: String, fallback: String, vararg args: Any?): String {
        val lookup = provider
        if (lookup != null) return lookup(key, fallback, args)
        return if (args.isEmpty()) fallback else String.format(fallback, *args)
    }
}

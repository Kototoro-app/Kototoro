package org.skepsun.kototoro.desktop.player

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform

/**
 * libmpv's client API refuses to build a player unless the process' numeric locale is "C": it prints
 * "Non-C locale detected. This is not supported." and `mpv_create` hands back null. Windows and macOS never show it
 * because their C runtimes start (and stay) in the "C" locale, but a Linux process takes `LC_NUMERIC` from the
 * environment — a desktop started as `LANG=en_US.UTF-8` therefore cannot create any player, which also takes Anime4K
 * page upscaling down with it, since that runs through libmpv too. mpv's own embedding documentation asks hosts to
 * `setlocale(LC_NUMERIC, "C")`; only the C locale's number formatting changes, while the UI keeps formatting through
 * [java.util.Locale], so what the user reads is unchanged.
 */
internal object MpvLocale {
    /** `LC_NUMERIC` from glibc's ordering; Apple's libc numbers the categories differently. */
    private val category = if (Platform.isMac()) 4 else 1

    @Volatile private var applied = false

    /** Why [ensure] could not switch the locale, for the player's "could not create" message. */
    @Volatile
    var failure: String? = null
        private set

    /** The numeric locale as the C library reports it right now, or null when libc cannot be asked. */
    val current: String?
        get() = runCatching { query() }.getOrNull()

    /** Whether [current] is the "C" locale the player needs. */
    val isC: Boolean
        get() = current?.let { it == "C" || it == "POSIX" } ?: false

    /**
     * Switches the process to the "C" numeric locale when it is not there yet and reports whether it ended up there.
     * Safe to call repeatedly and from any thread; a failure leaves the locale alone so that mpv reports the error.
     */
    @Synchronized
    fun ensure(): Boolean {
        if (applied) return true
        if (Platform.isWindows()) {
            // The JVM there never calls setlocale, so the C runtime's numeric locale stays "C".
            applied = true
            return true
        }
        if (isC) {
            applied = true
            return true
        }
        failure = null
        applied = runCatching {
            setlocale("C")
            isC
        }.getOrElse { error ->
            failure = "setlocale(LC_NUMERIC, \"C\") failed: ${error.message ?: error.javaClass.name}"
            false
        }
        if (!applied && failure == null) failure = "LC_NUMERIC is ${current ?: "unknown"} after setlocale"
        return applied
    }

    private fun query(): String? = setlocale(null)

    /** Reads the numeric locale back for a `null` [locale] (query) or switches to it, returning `setlocale`'s answer. */
    private fun setlocale(locale: String?): String? {
        val function: Function = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME).getFunction("setlocale")
        // A null String argument is JNA's NULL char*, which is how setlocale is asked for the current value.
        val pointer = function.invokePointer(arrayOf<Any?>(category, locale))
        return pointer?.getString(0, "UTF-8")
    }
}

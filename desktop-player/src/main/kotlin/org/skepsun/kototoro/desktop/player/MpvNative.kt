package org.skepsun.kototoro.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Path

/** The part of libmpv's client API (client.h) the player uses; strings are UTF-8 as mpv expects. */
@Suppress("FunctionName")
internal interface MpvNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(context: Pointer): Int
    fun mpv_terminate_destroy(context: Pointer)
    fun mpv_set_option_string(context: Pointer, name: String, value: String): Int
    /** [arguments] must end with null (a NULL-terminated `const char **`). */
    fun mpv_command(context: Pointer, arguments: Array<String?>): Int
    fun mpv_set_property_string(context: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(context: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_wait_event(context: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(context: Pointer)
    fun mpv_error_string(error: Int): String

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21

        const val END_REASON_ERROR = 4

        private val loaded = mutableMapOf<Path, MpvNative>()

        /** One binding per library file for the whole process; JNA keeps the DLL loaded anyway. */
        @Synchronized
        fun load(library: Path): MpvNative = loaded.getOrPut(library.toAbsolutePath().normalize()) {
            Native.load(library.toAbsolutePath().toString(), MpvNative::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }
    }
}

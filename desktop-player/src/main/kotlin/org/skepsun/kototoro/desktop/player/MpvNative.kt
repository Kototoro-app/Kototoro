package org.skepsun.kototoro.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
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
    fun mpv_render_context_create(context: PointerByReference, handle: Pointer, params: Pointer): Int
    fun mpv_render_context_render(context: Pointer, params: Pointer): Int
    fun mpv_render_context_free(context: Pointer)

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21

        const val END_REASON_ERROR = 4

        private val loaded = mutableMapOf<Path, MpvNative>()

        /**
         * One binding per library file for the whole process; JNA keeps the DLL loaded anyway. The numeric locale is
         * fixed here because every libmpv entry point (player, Anime4K page rendering) comes through this loader.
         */
        @Synchronized
        fun load(library: Path): MpvNative = loaded.getOrPut(library.toAbsolutePath().normalize()) {
            MpvLocale.ensure()
            Native.load(library.toAbsolutePath().toString(), MpvNative::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }
    }
}

/** render.h: each parameter is a native enum followed by an aligned data pointer. */
@Structure.FieldOrder("type", "data")
internal class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}

internal fun mpvRenderParameters(vararg values: Pair<Int, Pointer>): MpvRenderParam {
    val first = MpvRenderParam()
    val array = first.toArray(values.size + 1)
    values.forEachIndexed { index, (type, data) ->
        (array[index] as MpvRenderParam).apply { this.type = type; this.data = data; write() }
    }
    array.last().write() // MPV_RENDER_PARAM_INVALID terminator
    return first
}

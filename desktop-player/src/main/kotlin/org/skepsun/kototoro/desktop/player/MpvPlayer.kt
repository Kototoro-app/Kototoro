package org.skepsun.kototoro.desktop.player

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.nio.file.Path

/** A side track the stream comes with (subtitles or alternative audio), fetched with the stream's headers. */
data class MpvExternalTrack(val url: String, val language: String, val title: String? = null)

/** One thing to play: the URL, the headers the site needs, and the side tracks the extension found. */
data class MpvStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<MpvExternalTrack> = emptyList(),
    val audio: List<MpvExternalTrack> = emptyList(),
    val startSeconds: Double = 0.0,
    val title: String? = null,
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val title: String?,
    val language: String?,
    val selected: Boolean,
    val external: Boolean,
) {
    val label: String get() = listOfNotNull(title?.takeIf(String::isNotBlank), language?.takeIf(String::isNotBlank))
        .distinct().joinToString(" · ").ifBlank { "#$id" }
}

data class MpvState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    val subtitles: List<MpvTrack> = emptyList(),
    val audio: List<MpvTrack> = emptyList(),
    val error: String? = null,
)

class MpvException(message: String) : Exception(message)

/**
 * One libmpv instance. With [windowId] mpv renders into that native window (an AWT canvas' HWND); without it there is
 * no video or audio output at all, which is what the tests use. All calls are thread-safe; a daemon thread drains the
 * event queue and another samples the playback properties a few times per second into [state].
 */
class MpvPlayer(
    library: Path,
    windowId: Long? = null,
    options: Map<String, String> = emptyMap(),
) : Closeable {
    private val native = MpvNative.load(library)
    private val handle: Pointer = native.mpv_create() ?: throw MpvException("libmpv could not create a player")
    private val mutableState = MutableStateFlow(MpvState())
    val state: StateFlow<MpvState> = mutableState.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var pending: MpvStream? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val events: Thread
    private val sampler: Thread

    init {
        val defaults = linkedMapOf(
            "config" to "no", "terminal" to "no", "load-scripts" to "no", "ytdl" to "no", "osc" to "no",
            "input-default-bindings" to "no", "input-vo-keyboard" to "no", "input-cursor" to "no",
            "idle" to "yes", "keep-open" to "yes", "sub-auto" to "no", "hwdec" to "auto-safe",
            "cache" to "yes", "demuxer-max-bytes" to "150MiB", "network-timeout" to "30",
        )
        if (windowId != null) {
            defaults["wid"] = windowId.toString()
            defaults["force-window"] = "yes"
            defaults["vo"] = "gpu"
        } else {
            defaults["vo"] = "null"
            defaults["ao"] = "null"
        }
        try {
            for ((name, value) in defaults + options) check(native.mpv_set_option_string(handle, name, value), "option $name")
            check(native.mpv_initialize(handle), "initialize")
        } catch (error: Throwable) {
            native.mpv_terminate_destroy(handle)
            throw error
        }
        events = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
        sampler = Thread(::sampleLoop, "mpv-state").apply { isDaemon = true; start() }
    }

    /** Replaces whatever plays. Headers apply to the stream and to its side tracks. */
    fun load(stream: MpvStream) {
        requireOpen()
        val headers = stream.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        command("change-list", "http-header-fields", "clr", "")
        for ((name, value) in headers) command("change-list", "http-header-fields", "append", "$name: $value")
        stream.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let {
            setProperty("user-agent", it.value)
        }
        pending = stream
        mutableState.value = MpvState(loading = true, volume = mutableState.value.volume,
            muted = mutableState.value.muted, speed = mutableState.value.speed)
        val start = if (stream.startSeconds > 0) "start=${"%.3f".format(java.util.Locale.ROOT, stream.startSeconds)}" else ""
        command("loadfile", stream.url, "replace", "-1", start)
        stream.title?.let { setProperty("force-media-title", it) }
    }

    fun setPaused(paused: Boolean) = setProperty("pause", if (paused) "yes" else "no")
    fun togglePause() = setPaused(!mutableState.value.paused)
    fun seek(seconds: Double, relative: Boolean = false) =
        command("seek", "%.3f".format(java.util.Locale.ROOT, seconds), if (relative) "relative" else "absolute")
    fun setVolume(volume: Int) = setProperty("volume", volume.coerceIn(0, 130).toString())
    fun setMuted(muted: Boolean) = setProperty("mute", if (muted) "yes" else "no")
    fun setSpeed(speed: Double) = setProperty("speed", "%.2f".format(java.util.Locale.ROOT, speed.coerceIn(0.25, 4.0)))
    /** null turns subtitles off. */
    fun selectSubtitle(id: Int?) = setProperty("sid", id?.toString() ?: "no")
    fun selectAudio(id: Int) = setProperty("aid", id.toString())
    fun stop() = command("stop")

    /** User shaders applied to the video (see [MpvShaderLibrary]); an empty list turns enhancement off. */
    fun setShaders(files: List<Path>) {
        if (files.isEmpty()) command("change-list", "glsl-shaders", "clr", "")
        else command("change-list", "glsl-shaders", "set", files.joinToString(java.io.File.pathSeparator) { it.toAbsolutePath().toString() })
    }

    /** Raw property access for the rarer controls (and tests). */
    fun property(name: String): String? {
        requireOpen()
        val value = native.mpv_get_property_string(handle, name) ?: return null
        return try { value.getString(0, "UTF-8") } finally { native.mpv_free(value) }
    }

    fun setProperty(name: String, value: String) {
        requireOpen()
        check(native.mpv_set_property_string(handle, name, value), "set $name")
    }

    fun command(vararg arguments: String) {
        requireOpen()
        check(native.mpv_command(handle, arrayOf(*arguments, null)), arguments.first())
    }

    private fun eventLoop() {
        while (!closed) {
            val event = native.mpv_wait_event(handle, 0.5)
            when (event.getInt(0)) {
                MpvNative.EVENT_SHUTDOWN -> return
                MpvNative.EVENT_START_FILE -> mutableState.update { it.copy(loading = true, ended = false, error = null) }
                MpvNative.EVENT_FILE_LOADED -> onLoaded()
                MpvNative.EVENT_PLAYBACK_RESTART -> mutableState.update { it.copy(loading = false) }
                MpvNative.EVENT_END_FILE -> {
                    val data = event.getPointer(16)
                    val reason = data?.getInt(0) ?: 0
                    if (reason == MpvNative.END_REASON_ERROR) {
                        val error = data?.getInt(4) ?: 0
                        mutableState.update { it.copy(loading = false, loaded = false,
                            error = "无法播放：${native.mpv_error_string(error)}") }
                    }
                }
            }
        }
    }

    private fun onLoaded() {
        val stream = pending
        if (!closed && stream != null) {
            // Side tracks are added once the main file is open; "auto" keeps mpv's own selection rules.
            for (track in stream.subtitles) runCatching {
                command("sub-add", track.url, "auto", track.title ?: track.language, track.language)
            }
            for (track in stream.audio) runCatching {
                command("audio-add", track.url, "auto", track.title ?: track.language, track.language)
            }
        }
        mutableState.update { it.copy(loaded = true, loading = false, error = null) }
        sample()
    }

    private fun sampleLoop() {
        while (!closed) {
            try { sample() } catch (_: Exception) { }
            try { Thread.sleep(200) } catch (_: InterruptedException) { return }
        }
    }

    private fun sample() {
        if (closed) return
        val tracks = property("track-list")?.let(::tracks).orEmpty()
        mutableState.update { previous ->
            previous.copy(
                position = property("time-pos")?.toDoubleOrNull() ?: previous.position,
                duration = property("duration")?.toDoubleOrNull() ?: previous.duration,
                paused = property("pause") == "yes",
                buffering = property("paused-for-cache") == "yes",
                ended = property("eof-reached") == "yes",
                volume = property("volume")?.toDoubleOrNull()?.toInt() ?: previous.volume,
                muted = property("mute") == "yes",
                speed = property("speed")?.toDoubleOrNull() ?: previous.speed,
                subtitles = tracks.filter { it.type == "sub" },
                audio = tracks.filter { it.type == "audio" },
            )
        }
    }

    private fun tracks(text: String): List<MpvTrack> = try {
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = track[key]?.jsonPrimitive?.contentOrNull
            MpvTrack(
                id = track["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                type = text("type") ?: return@mapNotNull null,
                title = text("title"), language = text("lang"),
                selected = track["selected"]?.jsonPrimitive?.booleanOrNull ?: false,
                external = track["external"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun check(code: Int, what: String) {
        if (code < 0) throw MpvException("mpv $what: ${native.mpv_error_string(code)}")
    }

    private fun requireOpen() { if (closed) throw MpvException("player is closed") }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        native.mpv_wakeup(handle)
        sampler.interrupt()
        events.join(2000)
        sampler.join(2000)
        native.mpv_terminate_destroy(handle)
    }
}

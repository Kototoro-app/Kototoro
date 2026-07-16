package org.skepsun.kototoro.video.player

import android.graphics.Color
import android.view.Surface
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.CopyOnWriteArrayList
import android.util.Log
import java.io.File

class MpvPlayer(
	private val mpv: MPV,
) : MPV.EventObserver, MPV.LogObserver {

	interface Listener {
		fun onPositionChanged(positionMs: Long) = Unit
		fun onDurationChanged(durationMs: Long) = Unit
		fun onIsPlayingChanged(isPlaying: Boolean) = Unit
		fun onPlaybackEnded() = Unit
		fun onFileLoaded() = Unit
		fun onPlaybackFailed(message: String?) = Unit
		fun onSeek(positionMs: Long) = Unit
		fun onSubtitleTextChanged(text: String?) = Unit
	}

	private val listeners = CopyOnWriteArrayList<Listener>()
	private var isInitialized = false
	private var shouldAutoPlayAfterLoad: Boolean = false
	private var awaitingFileLoaded = false
	private var hasLoadedCurrentFile = false
	private var isStoppingForLoad = false
	private var lastPlaybackErrorMessage: String? = null

	var durationMs: Long = 0L
		private set
	var positionMs: Long = 0L
		private set
	var isPlaying: Boolean = false
		private set

	fun initialize() {
		if (isInitialized) return
		// Disable built-in ytdl_hook: Android devices don't have yt-dlp,
		// and the hook interferes with some Aniyomi video URLs.
		mpv.setOptionString("ytdl", "no")
		mpv.observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
		mpv.observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
		mpv.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
		mpv.observeProperty("eof-reached", MPV.mpvFormat.MPV_FORMAT_FLAG)
		mpv.observeProperty("sub-text", MPV.mpvFormat.MPV_FORMAT_STRING)
		mpv.addObserver(this)
		mpv.addLogObserver(this)
		isInitialized = true
	}

	fun attachSurface(surface: Surface) {
		mpv.attachSurface(surface)
	}

	fun detachSurface() {
		mpv.detachSurface()
	}

	fun release() {
		mpv.removeObserver(this)
		mpv.removeLogObserver(this)
		isInitialized = false
	}

	fun addListener(listener: Listener) {
		listeners.add(listener)
	}

	fun removeListener(listener: Listener) {
		listeners.remove(listener)
	}

	fun load(url: String, headers: Map<String, String>, startMs: Long? = null) {
		isStoppingForLoad = true
		runCatching { mpv.command("stop") }
		isStoppingForLoad = false

		shouldAutoPlayAfterLoad = true
		awaitingFileLoaded = true
		hasLoadedCurrentFile = false
		lastPlaybackErrorMessage = null
		
		if (startMs != null && startMs > 0L) {
			val seconds = startMs / 1000.0
			mpv.setOptionString("start", seconds.toString())
		} else {
			// Clear any previous start time to avoid carrying it over
			mpv.setOptionString("start", "none")
		}
		
		// Handle special headers separately for better compatibility
		val userAgent = headers.entries.find { it.key.equals("User-Agent", ignoreCase = true) }?.value
		val referer = headers.entries.find { it.key.equals("Referer", ignoreCase = true) }?.value
		
		if (!userAgent.isNullOrBlank()) {
			mpv.setOptionString("user-agent", userAgent)
		}
		if (!referer.isNullOrBlank()) {
			mpv.setOptionString("referrer", referer)
		}

		val otherHeaders = headers.filter { 
			!it.key.equals("User-Agent", ignoreCase = true) && !it.key.equals("Referer", ignoreCase = true) 
		}

		val headerValue = if (otherHeaders.isNotEmpty()) {
			// Each header must be in "Header: Value" format, and multiple headers are separated by ","
			// Note: MPV parsing of this string is tricky with commas, but this is the standard way.
			otherHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" }
		} else {
			""
		}
		mpv.setOptionString("http-header-fields", headerValue)
		
		Log.d("MpvPlayer", "load: $url with headers count=${headers.size}")
		mpv.command("loadfile", url, "replace")
	}

	fun setStreamingOptions(cacheSizeMb: Int? = null) {
		// Optimize for network streaming and seeking
		mpv.setOptionString("cache", "yes")
		mpv.setOptionString("cache-on-disk", "yes")
		mpv.setOptionString("demuxer-seekable-cache", "yes")
		mpv.setOptionString("demuxer-max-bytes", "${(cacheSizeMb ?: 128) * 1024 * 1024}")
		mpv.setOptionString("demuxer-max-back-bytes", "${(cacheSizeMb ?: 128) * 1024 * 1024 / 2}")
		
		// Readahead and buffer settings
		mpv.setOptionString("cache-secs", "30")
		mpv.setOptionString("demuxer-readahead-secs", "20")
		
		// Network timeout and retries
		mpv.setOptionString("network-timeout", "30")
		mpv.setOptionString("tls-verify", "no") // Some sources have cert issues
		
		// Faster seeking
		mpv.setOptionString("hr-seek", "default")
		
		Log.d("MpvPlayer", "setStreamingOptions applied")
	}

	fun setHardwareDecoding(enabled: Boolean) {
		mpv.setOptionString("hwdec", if (enabled) "auto" else "no")
	}

	fun setHardwareDecodingMode(mode: String) {
		if (mode.isBlank()) return
		mpv.setOptionString("hwdec", mode)
		Log.d("MpvPlayer", "setHardwareDecodingMode: $mode")
	}

	fun setVideoOutput(renderer: String) {
		if (renderer.isBlank()) return
		mpv.setOptionString("vo", renderer)
		Log.d("MpvPlayer", "setVideoOutput: $renderer")
	}

	fun play() {
		mpv.setPropertyBoolean("pause", false)
	}

	fun pause() {
		mpv.setPropertyBoolean("pause", true)
	}

	fun seekTo(positionMs: Long) {
		val seconds = positionMs / 1000.0
		mpv.command("seek", seconds.toString(), "absolute+keyframes")
		listeners.forEach { it.onSeek(positionMs) }
	}

	fun seekExact(positionMs: Long) {
		val seconds = positionMs / 1000.0
		mpv.command("seek", seconds.toString(), "absolute")
		listeners.forEach { it.onSeek(positionMs) }
	}

	fun setRate(speed: Double) {
		mpv.setPropertyDouble("speed", speed)
	}


	fun setAspectRatio(type: Int) {
		when (type) {
			1 -> { // Fill
				mpv.setPropertyDouble("panscan", 1.0)
				mpv.setOptionString("video-aspect-override", "-1")
				mpv.setOptionString("keepaspect", "yes")
			}
			2 -> { // 16:9
				mpv.setPropertyDouble("panscan", 0.0)
				mpv.setOptionString("video-aspect-override", "16/9")
				mpv.setOptionString("keepaspect", "yes")
			}
			3 -> { // 4:3
				mpv.setPropertyDouble("panscan", 0.0)
				mpv.setOptionString("video-aspect-override", "4/3")
				mpv.setOptionString("keepaspect", "yes")
			}
			4 -> { // Stretch
				mpv.setPropertyDouble("panscan", 0.0)
				mpv.setOptionString("video-aspect-override", "-1")
				mpv.setOptionString("keepaspect", "no")
			}
			else -> { // Default / Fit
				mpv.setPropertyDouble("panscan", 0.0)
				mpv.setOptionString("video-aspect-override", "-1")
				mpv.setOptionString("keepaspect", "yes")
			}
		}
	}
	fun setVolume(volume: Double) {
		mpv.setPropertyDouble("volume", volume)
	}

	fun applySubtitleStyle(
		fontSizeSp: Float,
		isBold: Boolean,
		isItalic: Boolean,
		textColor: Int,
		borderColor: Int,
		borderSize: Float,
		backgroundColor: Int,
		alignX: Int,
		position: Int,
	) {
		val fontWeight = when {
			isBold && isItalic -> "bold-italic"
			isBold -> "bold"
			else -> "normal"
		}
		val alignXValue = when (alignX) {
			0 -> "left"
			2 -> "right"
			else -> "center"
		}
		val mappedPosition = (100 - (position / 3f).toInt()).coerceIn(0, 100)

		runCatching { mpv.setOptionString("sub-font-size", fontSizeSp.toInt().coerceAtLeast(1).toString()) }
		runCatching { mpv.setPropertyString("sub-font-size", fontSizeSp.toInt().coerceAtLeast(1).toString()) }
		runCatching { mpv.setOptionString("sub-color", textColor.toMpvColor()) }
		runCatching { mpv.setPropertyString("sub-color", textColor.toMpvColor()) }
		runCatching { mpv.setOptionString("sub-border-color", borderColor.toMpvColor()) }
		runCatching { mpv.setPropertyString("sub-border-color", borderColor.toMpvColor()) }
		runCatching { mpv.setOptionString("sub-border-size", borderSize.coerceAtLeast(0f).toString()) }
		runCatching { mpv.setPropertyString("sub-border-size", borderSize.coerceAtLeast(0f).toString()) }
		runCatching { mpv.setOptionString("sub-back-color", backgroundColor.toMpvColor()) }
		runCatching { mpv.setPropertyString("sub-back-color", backgroundColor.toMpvColor()) }
		runCatching { mpv.setOptionString("sub-align-x", alignXValue) }
		runCatching { mpv.setPropertyString("sub-align-x", alignXValue) }
		runCatching { mpv.setOptionString("sub-pos", mappedPosition.toString()) }
		runCatching { mpv.setPropertyString("sub-pos", mappedPosition.toString()) }
		runCatching { mpv.setOptionString("sub-bold", if (isBold) "yes" else "no") }
		runCatching { mpv.setPropertyString("sub-bold", if (isBold) "yes" else "no") }
		runCatching { mpv.setOptionString("sub-italic", if (isItalic) "yes" else "no") }
		runCatching { mpv.setPropertyString("sub-italic", if (isItalic) "yes" else "no") }
		runCatching { mpv.setOptionString("sub-ass-force-style", "Weight=$fontWeight") }
	}

	fun applyCacheSettings(sizeMb: Int, cacheDir: File) {
		val clampedMb = sizeMb.coerceAtLeast(64)
		val bytes = clampedMb.toLong() * 1024L * 1024L
		if (!cacheDir.exists()) {
			cacheDir.mkdirs()
		}
		mpv.setOptionString("cache", "yes")
		mpv.setOptionString("cache-on-disk", "yes")
		mpv.setOptionString("demuxer-seekable-cache", "yes")
		mpv.setOptionString("demuxer-cache-dir", cacheDir.absolutePath)
		mpv.setOptionString("demuxer-max-bytes", bytes.toString())
		mpv.setOptionString("demuxer-max-back-bytes", (bytes / 2).toString())
		Log.d("MpvPlayer", "applyCacheSettings: ${clampedMb}MB dir=${cacheDir.absolutePath}")
	}

	fun applyShaderList(shaderPaths: String?) {
		Log.d("MpvPlayer", "applyShaderList: ${shaderPaths ?: "none"}")
		if (shaderPaths.isNullOrBlank()) {
			mpv.command("change-list", "glsl-shaders", "clr", "")
            return
        }
        mpv.command("change-list", "glsl-shaders", "set", shaderPaths)
    }

	data class TrackInfo(
		val id: Int,
		val type: String, // "video", "audio", "sub"
		val title: String?,
		val language: String?,
		val codec: String?,
		val isDefault: Boolean,
		val isSelected: Boolean,
	) {
		fun displayName(): String {
			val parts = mutableListOf<String>()
			if (!title.isNullOrBlank()) parts.add(title)
			if (!language.isNullOrBlank() && language != title) parts.add("[$language]")
			if (!codec.isNullOrBlank()) parts.add("($codec)")
			return if (parts.isEmpty()) "Track $id" else parts.joinToString(" ")
		}
	}

	fun getTrackList(): List<TrackInfo> {
		// Try multiple approaches to get track count
		val count = getTrackCount()
		Log.d("MpvPlayer", "getTrackList: track count=$count")
		if (count <= 0) return emptyList()

		return (0 until count).mapNotNull { i ->
			try {
				val id = getTrackPropertyInt("track-list/$i/id") ?: return@mapNotNull null
				val type = getTrackPropertyString("track-list/$i/type") ?: return@mapNotNull null
				val title = getTrackPropertyString("track-list/$i/title")
				val lang = getTrackPropertyString("track-list/$i/lang")
				val codec = getTrackPropertyString("track-list/$i/codec")
				val isDefault = getTrackPropertyFlag("track-list/$i/default")
				val isSelected = getTrackPropertyFlag("track-list/$i/selected")
				TrackInfo(id, type, title, lang, codec, isDefault, isSelected).also {
					Log.d("MpvPlayer", "  track[$i]: id=$id type=$type lang=$lang title=$title codec=$codec selected=$isSelected")
				}
			} catch (e: Exception) {
				Log.e("MpvPlayer", "getTrackList: failed to read track $i", e)
				null
			}
		}
	}

	private fun getTrackCount(): Int {
		// Method 1: Try as int property
		try {
			@Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
			val count: Int = mpv.getPropertyInt("track-list/count")!!
			Log.d("MpvPlayer", "getTrackCount via getPropertyInt: $count")
			return count
		} catch (e: Exception) {
			Log.d("MpvPlayer", "getTrackCount getPropertyInt failed: ${e.message}")
		}
		// Method 2: Try as string property
		try {
			val countStr = mpv.getPropertyString("track-list/count")
			val count = countStr?.toIntOrNull()
			if (count != null) {
				Log.d("MpvPlayer", "getTrackCount via getPropertyString: $count")
				return count
			}
			Log.d("MpvPlayer", "getTrackCount getPropertyString returned: '$countStr'")
		} catch (e: Exception) {
			Log.d("MpvPlayer", "getTrackCount getPropertyString failed: ${e.message}")
		}
		// Method 3: Probe by iterating until property lookup fails
		Log.d("MpvPlayer", "getTrackCount: probing by iteration")
		var probed = 0
		while (probed < 50) { // safety limit
			try {
				val type = mpv.getPropertyString("track-list/$probed/type")
				if (type.isNullOrEmpty()) break
				probed++
			} catch (e: Exception) {
				break
			}
		}
		Log.d("MpvPlayer", "getTrackCount via probing: $probed")
		return probed
	}

	private fun getTrackPropertyString(prop: String): String? {
		return try {
			mpv.getPropertyString(prop)
		} catch (e: Exception) {
			null
		}
	}

	private fun getTrackPropertyInt(prop: String): Int? {
		// Try int first, then string
		runCatching { mpv.getPropertyInt(prop) }.getOrNull()?.let { return it }
		return runCatching { mpv.getPropertyString(prop)?.toIntOrNull() }.getOrNull()
	}

	private fun getTrackPropertyFlag(prop: String): Boolean {
		// Try boolean first, then string "yes"/"true"
		runCatching { mpv.getPropertyBoolean(prop) }.getOrNull()?.let { return it }
		val str = runCatching { mpv.getPropertyString(prop) }.getOrNull()
		return str == "yes" || str == "true"
	}

	fun getSubtitleTracks(): List<TrackInfo> {
		val tracks = getTrackList().filter { it.type == "sub" }
		Log.d("MpvPlayer", "getSubtitleTracks: found ${tracks.size} tracks: ${tracks.map { "[id=${it.id} lang=${it.language} title=${it.title} codec=${it.codec} selected=${it.isSelected}]" }}")
		return tracks
	}

	fun getAudioTracks(): List<TrackInfo> = getTrackList().filter { it.type == "audio" }

	fun setSubtitleTrack(id: Int?) {
		if (id == null || id <= 0) {
			mpv.setPropertyString("sid", "no")
			// Explicitly hide subtitles - try boolean first, then string
			runCatching { mpv.setPropertyBoolean("sub-visibility", false) }
				.onFailure { runCatching { mpv.setPropertyString("sub-visibility", "no") } }
		} else {
			runCatching { mpv.setPropertyInt("sid", id) }.onFailure {
				mpv.setPropertyString("sid", id.toString())
			}
			// Explicitly enable subtitle rendering - try boolean first, then string
			runCatching { mpv.setPropertyBoolean("sub-visibility", true) }
				.onFailure { runCatching { mpv.setPropertyString("sub-visibility", "yes") } }
			// Also ensure OSD is enabled (needed for subtitle overlay)
			runCatching { mpv.setPropertyString("osd-level", "1") }
		}
		val currentSid = runCatching { mpv.getPropertyString("sid") }.getOrNull()
		val subVis = runCatching { mpv.getPropertyString("sub-visibility") }.getOrNull()
		val subVisBool = runCatching { mpv.getPropertyBoolean("sub-visibility") }.getOrNull()
		val subText = runCatching { mpv.getPropertyString("sub-text") }.getOrNull()
		val subScale = runCatching { mpv.getPropertyString("sub-scale") }.getOrNull()
		val blendSubs = runCatching { mpv.getPropertyString("blend-subtitles") }.getOrNull()
		Log.d("MpvPlayer", "setSubtitleTrack: requested=$id, sid=$currentSid, sub-visibility=$subVis/$subVisBool, sub-text='$subText', sub-scale=$subScale, blend-subs=$blendSubs")
	}

	fun setAudioTrack(id: Int) {
		mpv.setPropertyString("aid", id.toString())
		Log.d("MpvPlayer", "setAudioTrack: $id")
	}

	/**
	 * Add an external subtitle track by URL.
	 * Must be called after loadfile (ideally after FILE_LOADED event).
	 */
	fun addSubtitleTrack(url: String, title: String? = null, lang: String? = null) {
		try {
			// sub-add <url> [<flags> [<title> [<lang>]]]
			val args = mutableListOf("sub-add", url, "auto")
			args.add(title ?: "")
			args.add(lang ?: "")
			mpv.command(*args.toTypedArray())
			Log.d("MpvPlayer", "addSubtitleTrack: url=$url title=$title lang=$lang")
		} catch (e: Exception) {
			Log.e("MpvPlayer", "addSubtitleTrack failed: url=$url", e)
		}
	}

	/**
	 * Add an external audio track by URL.
	 */
	fun addAudioTrack(url: String, title: String? = null, lang: String? = null) {
		try {
			val args = mutableListOf("audio-add", url, "auto")
			args.add(title ?: "")
			args.add(lang ?: "")
			mpv.command(*args.toTypedArray())
			Log.d("MpvPlayer", "addAudioTrack: url=$url title=$title lang=$lang")
		} catch (e: Exception) {
			Log.e("MpvPlayer", "addAudioTrack failed: url=$url", e)
		}
	}

	fun getPropertyString(name: String): String? {
		return runCatching { mpv.getPropertyString(name) }.getOrNull()
	}

	override fun event(eventId: Int, node: MPVNode) {
		Log.v("MpvPlayer", "MPV Event: $eventId")
		when (eventId) {
			MPV.mpvEvent.MPV_EVENT_START_FILE -> Log.d("MpvPlayer", "EVENT_START_FILE")
			MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
				Log.d("MpvPlayer", "EVENT_FILE_LOADED")
				awaitingFileLoaded = false
				hasLoadedCurrentFile = true
				if (shouldAutoPlayAfterLoad) {
					mpv.setPropertyBoolean("pause", false)
					shouldAutoPlayAfterLoad = false
				}
				listeners.forEach { it.onFileLoaded() }
			}
			MPV.mpvEvent.MPV_EVENT_END_FILE -> {
				Log.d("MpvPlayer", "EVENT_END_FILE")
				val failedBeforeLoad = awaitingFileLoaded && !hasLoadedCurrentFile
				awaitingFileLoaded = false
				if (isStoppingForLoad) {
					Log.d("MpvPlayer", "EVENT_END_FILE ignored due to manual stop for loading")
				} else if (failedBeforeLoad) {
					Log.w("MpvPlayer", "EVENT_END_FILE before FILE_LOADED, treat as playback failure")
					listeners.forEach { it.onPlaybackFailed(lastPlaybackErrorMessage) }
				} else {
					listeners.forEach { it.onPlaybackEnded() }
				}
			}
			MPV.mpvEvent.MPV_EVENT_IDLE -> Log.d("MpvPlayer", "EVENT_IDLE")
			MPV.mpvEvent.MPV_EVENT_SHUTDOWN -> Log.d("MpvPlayer", "EVENT_SHUTDOWN")
		}
	}

	override fun eventProperty(property: String) = Unit

	override fun eventProperty(property: String, value: Long) = Unit

	override fun eventProperty(property: String, value: Double) {
		when (property) {
			"time-pos" -> {
				positionMs = (value * 1000).toLong()
				listeners.forEach { it.onPositionChanged(positionMs) }
			}
			"duration" -> {
				durationMs = (value * 1000).toLong()
				listeners.forEach { it.onDurationChanged(durationMs) }
			}
		}
	}

	override fun eventProperty(property: String, value: Boolean) {
		when (property) {
			"pause" -> {
				isPlaying = !value
				listeners.forEach { it.onIsPlayingChanged(isPlaying) }
			}
			"eof-reached" -> {
				if (value) {
					listeners.forEach { it.onPlaybackEnded() }
				}
			}
		}
	}

	override fun eventProperty(property: String, value: String) {
		when (property) {
			"sub-text" -> {
				listeners.forEach { it.onSubtitleTextChanged(value.ifEmpty { null }) }
			}
		}
	}

	override fun eventProperty(property: String, value: MPVNode) = Unit

	override fun logMessage(prefix: String, level: Int, text: String) {
		if (level > MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN) return
		val normalized = text.trim()
		if (normalized.isEmpty()) return
		if (
			normalized.contains("HTTP error", ignoreCase = true) ||
			normalized.contains("error", ignoreCase = true) ||
			normalized.contains("failed", ignoreCase = true)
		) {
			lastPlaybackErrorMessage = "[$prefix] $normalized"
			Log.w("MpvPlayer", "Captured playback error log: $lastPlaybackErrorMessage")
		}
	}
}

private fun Int.toMpvColor(): String {
	return String.format(
		"#%02X%02X%02X%02X",
		Color.alpha(this),
		Color.red(this),
		Color.green(this),
		Color.blue(this),
	)
}

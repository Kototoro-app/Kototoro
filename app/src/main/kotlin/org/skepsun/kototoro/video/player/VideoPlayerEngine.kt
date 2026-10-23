package org.skepsun.kototoro.video.player

import android.net.Uri
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.animesource.model.Track
import java.util.Locale
import org.skepsun.kototoro.video.domain.PlaybackSubtitle
import org.skepsun.kototoro.video.domain.SubtitleOrigin

/**
 * Human name for an ISO-639 track language, so the track menu reads `English` instead of `en`.
 * Unknown codes fall back to the tag as-is.
 */
internal fun trackLanguageName(language: String): String {
    val tag = language.trim()
    if (tag.isEmpty()) return tag
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale).ifBlank { tag }
}

/**
 * Track codecs arrive as raw MIME types or media3 codec strings (`application/x-quicktime-tx3g`,
 * `mp4a.40.2`). Only formats a viewer can recognise are kept; container MIME types say nothing
 * about the track and used to fill the menu with noise.
 */
internal fun trackCodecName(codec: String): String {
    val value = codec.trim().lowercase(Locale.ROOT)
    return when {
        value.isEmpty() -> ""
        value.contains("pgs") -> "PGS"
        value.contains("vobsub") || value.contains("dvdsub") -> "VobSub"
        value.contains("tx3g") || value.contains("mov_text") -> "Timed text"
        value.endsWith("vtt") -> "WebVTT"
        value.contains("ttml") || value.contains("dfxp") -> "TTML"
        value.contains("ssa") || value.contains("ass") -> "ASS"
        value.contains("subrip") || value.endsWith("srt") -> "SubRip"
        value.startsWith("mp4a") -> "AAC"
        value.startsWith("ac-3") || value.startsWith("ac3") -> "Dolby Digital"
        value.startsWith("ec-3") || value.startsWith("eac3") -> "Dolby Digital Plus"
        value.startsWith("opus") -> "Opus"
        value.startsWith("vorbis") -> "Vorbis"
        value.startsWith("flac") -> "FLAC"
        value.startsWith("pcm") -> "PCM"
        value.startsWith("mp3") || value.startsWith("mpeg") -> "MP3"
        value.contains("/") -> ""
        else -> codec.trim()
    }
}

enum class PlaybackMediaKind {
    AUTO,
    HLS,
    DASH,
    PROGRESSIVE,
    TORRENT,
}

data class PlaybackRequest(
    val requestId: String,
    val uri: Uri,
    val mediaKind: PlaybackMediaKind = PlaybackMediaKind.AUTO,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<PlaybackSubtitle> = emptyList(),
    val externalAudio: List<Track> = emptyList(),
    val startPositionMs: Long = 0L,
)

enum class VideoPlaybackStatus {
    IDLE,
    PREPARING,
    BUFFERING,
    READY,
    ENDED,
    FAILED,
}

data class VideoPlaybackSnapshot(
    val requestId: String? = null,
    val status: VideoPlaybackStatus = VideoPlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

interface VideoPlayerEngine {
    interface Listener {
        fun onPositionChanged(positionMs: Long) = Unit
        fun onDurationChanged(durationMs: Long) = Unit
        fun onIsPlayingChanged(isPlaying: Boolean) = Unit
        fun onPlaybackEnded() = Unit
        fun onFileLoaded() = Unit
        fun onPlaybackFailed(message: String?) = Unit
        fun onSeek(positionMs: Long) = Unit
        fun onSubtitleTextChanged(text: String?) = Unit
        fun onSubtitleTracksChanged(tracks: List<TrackInfo>) = Unit
        fun onRenderedFirstFrame() = Unit
    }

    data class TrackInfo(
        val id: String,
        val type: String,
        val title: String?,
        val language: String?,
        val codec: String?,
        val origin: SubtitleOrigin? = null,
        val isDefault: Boolean,
        val isSelected: Boolean,
    ) {
        fun displayName(): String {
            val parts = mutableListOf<String>()
            title?.takeIf(String::isNotBlank)?.let(parts::add)
            language?.takeIf(String::isNotBlank)
                ?.let(::trackLanguageName)
                ?.takeUnless { name -> parts.any { it.equals(name, ignoreCase = true) } }
                ?.let(parts::add)
            codec?.takeIf(String::isNotBlank)
                ?.let(::trackCodecName)
                ?.takeIf(String::isNotBlank)
                ?.takeUnless { name -> parts.any { it.equals(name, ignoreCase = true) } }
                ?.let(parts::add)
            return parts.joinToString(" · ").ifBlank { "Track $id" }
        }
    }

    val player: Player
    val durationMs: Long
    val positionMs: Long
    val isPlaying: Boolean
    val snapshot: VideoPlaybackSnapshot

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun attachPlayerView(view: PlayerView)
    fun setVideoSurface(surface: Surface?)
    fun clearVideoSurface()
    fun load(request: PlaybackRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekExact(positionMs: Long) = seekTo(positionMs)
    fun setRate(speed: Double)
    fun setAspectRatio(type: Int)
    fun setVolume(volume: Double)
    fun setVolumeBoost(enabled: Boolean)
    fun getSubtitleTracks(): List<TrackInfo>
    fun getAudioTracks(): List<TrackInfo>
    fun setSubtitleTrack(id: String?)
    fun setAudioTrack(id: String)
    fun getPropertyString(name: String): String?
    fun release()
}

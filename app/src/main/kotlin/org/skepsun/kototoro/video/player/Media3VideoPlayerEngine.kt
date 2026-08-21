package org.skepsun.kototoro.video.player

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.video.domain.PlaybackSubtitle
import org.skepsun.kototoro.video.domain.SubtitleOrigin
import java.util.concurrent.CopyOnWriteArraySet
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class Media3VideoPlayerEngine(
    context: Context,
    private val httpClient: OkHttpClient,
    private val cache: Cache,
    decoderMode: VideoDecoderMode,
) : VideoPlayerEngine, Player.Listener, AnalyticsListener {
    private val appContext = context.applicationContext

    private data class TrackTarget(
        val type: Int,
        val group: Tracks.Group,
        val trackIndex: Int,
    )

    private data class PendingSeekDiagnostic(
        val targetPositionMs: Long,
        val originPositionMs: Long,
        val requestedAtMs: Long,
    )

    private val listeners = CopyOnWriteArraySet<VideoPlayerEngine.Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val trackSelector = DefaultTrackSelector(context)
    private val subtitleParserFactory = DefaultSubtitleParserFactory()
    private val renderersFactory = createRenderersFactory(context, decoderMode)
    private val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setTrackSelector(trackSelector)
        .setSeekParameters(Media3PlaybackConfig.seekParameters())
        .setLoadControl(Media3PlaybackConfig.loadControl())
        .setLivePlaybackSpeedControl(Media3PlaybackConfig.livePlaybackSpeedControl())
        .build()
    private val trackTargets = LinkedHashMap<String, TrackTarget>()
    private var currentRequest: PlaybackRequest? = null
    private var lastDuration = C.TIME_UNSET
    private var lastIsPlaying = false
    private var fileLoadedForRequest: String? = null
    private var currentStatus = VideoPlaybackStatus.IDLE
    private var currentError: String? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var volumeBoostEnabled = false
    private var playerView: PlayerView? = null
    private var videoDecoderName: String? = null
    private var droppedVideoFrameCount = 0
    private var pendingSeekDiagnostic: PendingSeekDiagnostic? = null

    private fun createRenderersFactory(context: Context, mode: VideoDecoderMode): RenderersFactory = when (mode) {
        VideoDecoderMode.HARDWARE_ONLY -> DefaultRenderersFactory(context)
        VideoDecoderMode.HARDWARE_PREFERRED -> CloudStreamCompatibleRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        VideoDecoderMode.SOFTWARE_PREFERRED -> CloudStreamCompatibleRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            if (currentRequest != null) {
                val position = positionMs
                listeners.forEach { it.onPositionChanged(position) }
            }
            handler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    init {
        exoPlayer.addListener(this)
        exoPlayer.addAnalyticsListener(this)
        handler.post(positionTicker)
    }

    override val player: Player
        get() = exoPlayer

    override val durationMs: Long
        get() = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L

    override val positionMs: Long
        get() = exoPlayer.currentPosition.coerceAtLeast(0L)

    override val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    override val snapshot: VideoPlaybackSnapshot
        get() = VideoPlaybackSnapshot(
            requestId = currentRequest?.requestId,
            status = currentStatus,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            error = currentError,
        )

    override fun addListener(listener: VideoPlayerEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: VideoPlayerEngine.Listener) {
        listeners -= listener
    }

    override fun attachPlayerView(view: PlayerView) {
        playerView?.takeIf { it !== view }?.player = null
        playerView = view
        view.useController = false
        view.subtitleView?.visibility = android.view.View.GONE
        view.player = null
        view.player = exoPlayer
    }

    override fun setVideoSurface(surface: Surface?) {
        if (surface == null) {
            exoPlayer.clearVideoSurface()
        } else {
            exoPlayer.setVideoSurface(surface)
        }
    }

    override fun clearVideoSurface() {
        exoPlayer.clearVideoSurface()
    }

    override fun load(request: PlaybackRequest) {
        currentRequest = request
        currentError = null
        currentStatus = VideoPlaybackStatus.PREPARING
        fileLoadedForRequest = null
        lastDuration = C.TIME_UNSET
        videoDecoderName = null
        droppedVideoFrameCount = 0
        pendingSeekDiagnostic = null
        val mediaSource = createMediaSource(request)
        exoPlayer.setMediaSource(mediaSource, request.startPositionMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        Log.d(TAG, "prepare request=${request.requestId} kind=${request.mediaKind} headers=${request.headers.keys}")
    }

    private fun createMediaSource(request: PlaybackRequest): MediaSource {
        val safeHeaders = request.headers.filterKeys { key ->
            !key.equals("Host", true) &&
                !key.equals("Connection", true) &&
                !key.equals("Content-Length", true)
        }
        val httpUpstream = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(safeHeaders)
        val defaultUpstream = DefaultDataSource.Factory(appContext, httpUpstream)
        val cachedFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(defaultUpstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val isNetworkUri = request.uri.scheme.equals("http", true) || request.uri.scheme.equals("https", true)
        val dataSourceFactory: DataSource.Factory = if (isNetworkUri) {
            cachedFactory
        } else {
            defaultUpstream
        }
        val extractorsFactory = DefaultExtractorsFactory()
            .setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_MERGE_FRAGMENTED_SIDX)
        val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .setLiveTargetOffsetMs(Media3PlaybackConfig.PREFERRED_LIVE_OFFSET_MS)
        val item = MediaItem.Builder()
            .setMediaId(request.requestId)
            .setUri(request.uri)
            .setMimeType(request.mediaKind.mimeType())
            .build()
        val primary = sourceFactory.createMediaSource(item)
        val subtitleSources = request.subtitles.map(::createSubtitleMediaSource)
        val audioSources = request.externalAudio.map { track ->
            sourceFactory.createMediaSource(
                MediaItem.Builder()
                    .setMediaId("${request.requestId}:audio:${track.url.hashCode()}")
                    .setUri(track.url)
                    .build(),
            )
        }
        val mergedSources = listOf(primary) + subtitleSources + audioSources
        return if (mergedSources.size == 1) primary else MergingMediaSource(*mergedSources.toTypedArray())
    }

    private fun createSubtitleDataSourceFactory(headers: Map<String, String>): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(headers.filterUnsafeRequestHeaders())
        return DefaultDataSource.Factory(appContext, upstream)
    }

    private fun createSubtitleMediaSource(track: PlaybackSubtitle): MediaSource {
        val configuration = subtitleConfiguration(track)
        val inputFormat = Format.Builder()
            .setId(configuration.id)
            .setSampleMimeType(configuration.mimeType)
            .setLanguage(configuration.language)
            .setLabel(configuration.label)
            .setSelectionFlags(configuration.selectionFlags)
            .setRoleFlags(configuration.roleFlags)
            .build()
        check(subtitleParserFactory.supportsFormat(inputFormat)) {
            "Unsupported external subtitle MIME type: ${configuration.mimeType}"
        }
        val extractorsFactory = ExtractorsFactory {
            arrayOf(SubtitleExtractor(subtitleParserFactory.create(inputFormat), inputFormat))
        }
        return ProgressiveMediaSource.Factory(
            createSubtitleDataSourceFactory(track.headers),
            extractorsFactory,
        ).createMediaSource(MediaItem.fromUri(configuration.uri))
    }

    private fun subtitleConfiguration(track: PlaybackSubtitle): MediaItem.SubtitleConfiguration {
        val uri = requireNotNull(track.uri) { "External subtitle ${track.id} has no URI" }
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setId(track.id)
            .setLanguage(track.languageTag?.takeIf(String::isNotBlank))
            .setLabel(track.label.takeIf(String::isNotBlank))
            .setMimeType(SubtitleMimeTypeResolver.resolve(track.mimeType, uri.toString()))
            .build()
    }

    override fun play() = exoPlayer.play()

    override fun pause() = exoPlayer.pause()

    override fun seekTo(positionMs: Long) {
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        val diagnostic = PendingSeekDiagnostic(
            targetPositionMs = targetPositionMs,
            originPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            requestedAtMs = SystemClock.elapsedRealtime(),
        )
        pendingSeekDiagnostic = diagnostic
        Log.i(TAG, "seek requested ${diagnostic.describe()} ${playbackDiagnosticState()}")
        exoPlayer.seekTo(targetPositionMs)
        handler.postDelayed({ logMissingVideoFrameAfterSeek(diagnostic) }, SEEK_FRAME_DIAGNOSTIC_TIMEOUT_MS)
        listeners.forEach { it.onSeek(targetPositionMs) }
    }

    override fun setRate(speed: Double) {
        exoPlayer.setPlaybackSpeed(speed.toFloat().coerceIn(0.25f, 4f))
    }

    override fun setAspectRatio(type: Int) {
        playerView?.resizeMode = when (type) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            3 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            4 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun setVolume(volume: Double) {
        exoPlayer.volume = (volume / 100.0).toFloat().coerceIn(0f, 1f)
    }

    override fun setVolumeBoost(enabled: Boolean) {
        volumeBoostEnabled = enabled
        applyLoudnessEnhancer()
    }

    private fun applyLoudnessEnhancer() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        if (!volumeBoostEnabled || exoPlayer.audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        loudnessEnhancer = runCatching {
            LoudnessEnhancer(exoPlayer.audioSessionId).apply {
                setTargetGain(600)
                enabled = true
            }
        }.onFailure { Log.w(TAG, "Unable to enable volume boost", it) }.getOrNull()
    }

    override fun getSubtitleTracks(): List<VideoPlayerEngine.TrackInfo> = buildTracks(C.TRACK_TYPE_TEXT, "sub")

    override fun getAudioTracks(): List<VideoPlayerEngine.TrackInfo> = buildTracks(C.TRACK_TYPE_AUDIO, "audio")

    private fun buildTracks(trackType: Int, label: String): List<VideoPlayerEngine.TrackInfo> {
        trackTargets.entries.removeAll { it.value.type == trackType }
        val result = mutableListOf<VideoPlayerEngine.TrackInfo>()
        val externalSubtitleIds = currentRequest?.subtitles?.mapTo(HashSet(), PlaybackSubtitle::id).orEmpty()
        exoPlayer.currentTracks.groups.filter { it.type == trackType }.forEachIndexed { groupIndex, group ->
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val configuredId = format.id?.takeIf(String::isNotBlank)
                val id = configuredId ?: "track:$trackType:${group.mediaTrackGroup.id}:$groupIndex:$index"
                trackTargets[id] = TrackTarget(trackType, group, index)
                result += VideoPlayerEngine.TrackInfo(
                    id = id,
                    type = label,
                    title = format.label,
                    language = format.language,
                    codec = format.codecs ?: format.sampleMimeType,
                    origin = if (trackType == C.TRACK_TYPE_TEXT) {
                        if (id in externalSubtitleIds) {
                            currentRequest?.subtitles?.firstOrNull { it.id == id }?.origin
                        } else {
                            SubtitleOrigin.EMBEDDED
                        }
                    } else {
                        null
                    },
                    isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                    isSelected = group.isTrackSelected(index),
                )
            }
        }
        return result
    }

    override fun setSubtitleTrack(id: String?) {
        val builder = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (id == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val target = trackTargets[id] ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.addOverride(TrackSelectionOverride(target.group.mediaTrackGroup, target.trackIndex))
        }
        trackSelector.parameters = builder.build()
    }

    override fun setAudioTrack(id: String) {
        val target = trackTargets[id] ?: return
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(target.group.mediaTrackGroup, target.trackIndex))
            .build()
    }

    override fun getPropertyString(name: String): String? {
        val video = exoPlayer.videoFormat
        val audio = exoPlayer.audioFormat
        return when (name) {
            "hwdec-current", "video-decoder" -> videoDecoderName ?: "MediaCodec"
            "vo", "video-out-params/vo" -> "Media3"
            "video-codec" -> video?.codecs ?: video?.sampleMimeType
            "audio-codec-name" -> audio?.codecs ?: audio?.sampleMimeType
            "video-params/w" -> video?.width?.takeIf { it > 0 }?.toString()
            "video-params/h" -> video?.height?.takeIf { it > 0 }?.toString()
            "estimated-vf-fps", "video-params/fps", "container-fps" ->
                video?.frameRate?.takeIf { it > 0f }?.toString()
            else -> null
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!isCurrentRequestEvent()) return
        currentStatus = when (playbackState) {
            Player.STATE_IDLE -> if (currentError == null) VideoPlaybackStatus.IDLE else VideoPlaybackStatus.FAILED
            Player.STATE_BUFFERING -> VideoPlaybackStatus.BUFFERING
            Player.STATE_READY -> VideoPlaybackStatus.READY
            Player.STATE_ENDED -> VideoPlaybackStatus.ENDED
            else -> VideoPlaybackStatus.IDLE
        }
        if (playbackState == Player.STATE_READY) {
            val requestId = currentRequest?.requestId
            if (requestId != null && fileLoadedForRequest != requestId) {
                fileLoadedForRequest = requestId
                listeners.forEach { it.onFileLoaded() }
            }
            dispatchDuration()
        }
        if (playbackState == Player.STATE_ENDED) listeners.forEach { it.onPlaybackEnded() }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (lastIsPlaying == isPlaying) return
        lastIsPlaying = isPlaying
        listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        dispatchDuration()
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (!isCurrentRequestEvent()) return
        listeners.forEach { it.onSubtitleTracksChanged(getSubtitleTracks()) }
    }

    private fun dispatchDuration() {
        val duration = durationMs
        if (duration == lastDuration || duration <= 0L) return
        lastDuration = duration
        listeners.forEach { it.onDurationChanged(duration) }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (!isCurrentRequestEvent()) return
        val classified = Media3PlaybackErrorClassifier.classify(error)
        currentError = "${classified.category}: ${error.errorCodeName}: ${error.message.orEmpty()}".trim()
        currentStatus = VideoPlaybackStatus.FAILED
        Log.w(TAG, "Playback failed request=${currentRequest?.requestId}: $currentError", error)
        listeners.forEach { it.onPlaybackFailed(currentError) }
    }

    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
        if (!isCurrentRequestEvent()) return
        val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").takeIf(String::isNotBlank)
        listeners.forEach { it.onSubtitleTextChanged(text) }
    }

    override fun onRenderedFirstFrame() {
        if (!isCurrentRequestEvent()) return
        pendingSeekDiagnostic?.let { diagnostic ->
            val elapsedMs = SystemClock.elapsedRealtime() - diagnostic.requestedAtMs
            Log.i(
                TAG,
                "first video frame after seek elapsedMs=$elapsedMs " +
                    "${diagnostic.describe()} ${playbackDiagnosticState()}",
            )
            pendingSeekDiagnostic = null
        }
        listeners.forEach { it.onRenderedFirstFrame() }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        if (!isCurrentRequestEvent()) return
        Log.i(
            TAG,
            "video format mime=${format.sampleMimeType} codecs=${format.codecs} " +
                "size=${format.width}x${format.height} fps=${format.frameRate} " +
                "decoderReuse=${decoderReuseEvaluation?.result}",
        )
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoderName = decoderName
        Log.i(TAG, "video decoder initialized name=$decoderName durationMs=$initializationDurationMs")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        if (!isCurrentRequestEvent() || droppedFrames <= 0) return
        droppedVideoFrameCount += droppedFrames
        Log.w(
            TAG,
            "video frames dropped count=$droppedFrames total=$droppedVideoFrameCount windowMs=$elapsedMs " +
                playbackDiagnosticState(),
        )
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        applyLoudnessEnhancer()
    }

    override fun release() {
        handler.removeCallbacks(positionTicker)
        pendingSeekDiagnostic = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        playerView?.player = null
        playerView = null
        exoPlayer.removeListener(this)
        exoPlayer.removeAnalyticsListener(this)
        exoPlayer.release()
        listeners.clear()
    }

    private fun isCurrentRequestEvent(): Boolean {
        val expected = currentRequest?.requestId ?: return false
        return exoPlayer.currentMediaItem?.mediaId == expected
    }

    private fun logMissingVideoFrameAfterSeek(diagnostic: PendingSeekDiagnostic) {
        if (pendingSeekDiagnostic !== diagnostic || !isCurrentRequestEvent()) return
        val elapsedMs = SystemClock.elapsedRealtime() - diagnostic.requestedAtMs
        Log.w(
            TAG,
            "no video frame after seek elapsedMs=$elapsedMs ${diagnostic.describe()} ${playbackDiagnosticState()}",
        )
    }

    private fun PendingSeekDiagnostic.describe(): String =
        "originMs=$originPositionMs targetMs=$targetPositionMs"

    private fun playbackDiagnosticState(): String =
        "request=${currentRequest?.requestId} state=${playbackStateLabel(exoPlayer.playbackState)} " +
            "isPlaying=${exoPlayer.isPlaying} playWhenReady=${exoPlayer.playWhenReady} " +
            "positionMs=${exoPlayer.currentPosition} bufferedMs=${exoPlayer.bufferedPosition} " +
            "decoder=${videoDecoderName ?: "unknown"} droppedFrames=$droppedVideoFrameCount"

    private fun playbackStateLabel(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    private fun PlaybackMediaKind.mimeType(): String? = when (this) {
        PlaybackMediaKind.HLS -> MimeTypes.APPLICATION_M3U8
        PlaybackMediaKind.DASH -> MimeTypes.APPLICATION_MPD
        PlaybackMediaKind.PROGRESSIVE,
        PlaybackMediaKind.AUTO,
        PlaybackMediaKind.TORRENT,
        -> null
    }

    private fun Map<String, String>.filterUnsafeRequestHeaders(): Map<String, String> = filterKeys { key ->
        !key.equals("Host", true) &&
            !key.equals("Connection", true) &&
            !key.equals("Content-Length", true)
    }

    private companion object {
        const val TAG = "Media3Player"
        const val POSITION_TICK_MS = 250L
        const val SEEK_FRAME_DIAGNOSTIC_TIMEOUT_MS = 2_500L
    }
}

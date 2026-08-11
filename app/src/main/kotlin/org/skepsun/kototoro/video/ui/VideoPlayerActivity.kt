package org.skepsun.kototoro.video.ui

import android.os.Bundle
import android.view.View
import android.content.res.Configuration
import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.app.PictureInPictureParams
import android.provider.MediaStore
import android.util.Rational
import android.util.Base64
import android.view.PixelCopy
import android.view.Surface
import android.view.WindowManager
import android.util.Log
import org.skepsun.kototoro.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.parser.tvbox.TVBoxPlayback
import org.skepsun.kototoro.core.ui.BaseComposeFullscreenActivity
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.ReaderIntent
import androidx.core.net.toUri
import androidx.media3.ui.PlayerView
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource as ParsersContentSource
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamContentRepository
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPlaybackEvent
import org.skepsun.kototoro.cloudstream.runtime.isCloudstreamStructuredLocator
import org.skepsun.kototoro.cloudstream.runtime.resolveCloudstreamEpisodeTitle
import javax.inject.Inject
import org.skepsun.kototoro.reader.ui.ScreenOrientationHelper
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.net.Uri
import java.net.URLDecoder
import android.media.AudioManager
import android.provider.Settings
import android.content.Context
import java.io.File
import java.net.URI
import kotlin.math.abs
import okhttp3.Headers
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.history.domain.HistoryUpdateUseCase
import org.skepsun.kototoro.readingrecord.data.ReadingRecordRepository
import org.skepsun.kototoro.reader.ui.ReaderNavigationCallback
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.prefs.Anime4KPreset
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.VideoEnhancementAlgorithm
import org.skepsun.kototoro.video.player.Media3VideoPlayerEngine
import org.skepsun.kototoro.video.player.EnhancedVideoSurfaceView
import org.skepsun.kototoro.video.player.VideoEnhancementConfig
import org.skepsun.kototoro.video.player.PlaybackMediaKind
import org.skepsun.kototoro.video.player.PlaybackRequest
import org.skepsun.kototoro.video.player.PlaybackRequestNormalizer
import org.skepsun.kototoro.video.player.VideoPlayerEngine
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy
import org.skepsun.kototoro.video.data.VideoCache
import org.skepsun.kototoro.video.data.ExternalPlayerHelper
import org.skepsun.kototoro.video.data.TorrentStreamService
import org.skepsun.kototoro.video.data.isTorrentLocator
import org.skepsun.kototoro.video.data.unwrapPngPrefixedStream
import org.skepsun.kototoro.video.performance.DevicePerformanceClassifier
import org.skepsun.kototoro.video.performance.DevicePerformanceInfo
import org.skepsun.kototoro.video.performance.EffectiveVideoPlaybackConfig
import org.skepsun.kototoro.video.performance.PlaybackFailureCategory
import org.skepsun.kototoro.video.performance.PlaybackFallbackController
import org.skepsun.kototoro.video.performance.PlaybackFallbackReason
import org.skepsun.kototoro.video.performance.PlaybackSessionDiagnostics
import org.skepsun.kototoro.video.performance.VideoPlaybackPolicy
import org.skepsun.kototoro.video.domain.resolveCloudstreamVideo
import org.skepsun.kototoro.video.domain.isSuspiciousCloudstreamPlaybackDuration
import org.skepsun.kototoro.video.domain.isStalledCloudstreamPlayback
import org.skepsun.kototoro.video.domain.sortedCloudstreamVideos
import org.skepsun.kototoro.video.danmaku.VideoDanmakuController
import org.skepsun.kototoro.video.danmaku.DanmakuSettings
import org.skepsun.kototoro.video.danmaku.DanmakuSourceManager
import org.skepsun.kototoro.video.dlna.DlnaController
import org.skepsun.kototoro.video.dlna.DlnaDevice
import org.skepsun.kototoro.video.dlna.SsdpDiscovery
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.ui.SpaceSwitcherDelegate
import org.skepsun.kototoro.space.domain.awaitCompletion
import com.bytedance.danmaku.render.engine.DanmakuView
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.video.ui.compose.VideoPlayerAction
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlState
import org.skepsun.kototoro.video.ui.compose.VideoGestureOverlayState
import org.skepsun.kototoro.video.ui.compose.VideoGestureOverlays
import org.skepsun.kototoro.video.ui.compose.VideoSubtitleOverlay
import org.skepsun.kototoro.video.ui.compose.VideoSubtitleOverlayState
import org.skepsun.kototoro.video.ui.compose.VideoScreenLockOverlay
import org.skepsun.kototoro.video.ui.compose.VideoSeekFeedback
import org.skepsun.kototoro.video.ui.compose.VideoSeekFeedbackState
import org.skepsun.kototoro.video.ui.compose.VideoActionDialog
import org.skepsun.kototoro.video.ui.compose.VideoActionDialogItem
import org.skepsun.kototoro.video.ui.compose.VideoActionDialogState
import org.skepsun.kototoro.video.ui.compose.VideoSubtitleSettingsDialog
import org.skepsun.kototoro.video.ui.compose.VideoSubtitleSettingsDialogState
import org.skepsun.kototoro.video.ui.compose.VideoChapterDialog
import org.skepsun.kototoro.video.ui.compose.VideoChapterDialogState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControls
import org.skepsun.kototoro.video.ui.compose.VideoPlayerInfoDialog
import org.skepsun.kototoro.video.ui.compose.VideoPlayerNativeInitErrorDialog
import org.skepsun.kototoro.video.ui.compose.PlayerMenuPlacement
import org.skepsun.kototoro.video.ui.compose.VideoSelectionDialog
import org.skepsun.kototoro.video.ui.compose.VideoSelectionDialogState
import org.skepsun.kototoro.video.ui.compose.VideoSuperResolutionDialog
import org.skepsun.kototoro.video.ui.compose.VideoSuperResolutionDialogState
import org.skepsun.kototoro.video.ui.compose.DlnaDeviceDialog
import org.skepsun.kototoro.video.ui.compose.DlnaDeviceDialogState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerRenderLayer

@AndroidEntryPoint
class VideoPlayerActivity : BaseComposeFullscreenActivity(), ReaderNavigationCallback {
    companion object {
        private const val CLOUDSTREAM_PLAYBACK_HEALTH_CHECK_DELAY_MS = 5_000L
        private const val ENABLE_M3U8_PROXY_CACHE = false
        private const val TORRENT_VIDEO_MARKER = "kototoro:torrent"
        private const val HLS_VIDEO_MARKER = "kototoro:hls"
    }

    private data class PlayerSettingsAction(
        val title: String,
        val subtitle: String? = null,
        val iconRes: Int,
        val isChecked: Boolean? = null,
        val onClick: () -> Unit,
    )

    private enum class PlayerUiState {
        Hidden,
        ControlsVisible,
        Locked,
    }

    private val chaptersViewModel: VideoChaptersViewModel by viewModels()

    @Inject
    lateinit var appSettings: AppSettings

    private lateinit var devicePerformanceInfo: DevicePerformanceInfo
    private lateinit var effectivePlaybackConfig: EffectiveVideoPlaybackConfig
    private var playbackConfigOverride: EffectiveVideoPlaybackConfig? = null
    private val shownFallbackHints = mutableSetOf<PlaybackFallbackReason>()
    private val shownPlaybackErrorHints = mutableSetOf<PlaybackFailureCategory>()
    private val playbackDiagnostics = PlaybackSessionDiagnostics()
    private var hasCurrentMediaLoaded = false
    private var playbackHealthCheckGeneration = 0L
    private var suspiciousAdRetryCount = 0

    private var videoPlayer: VideoPlayerEngine? = null
    private var isUiVisible: Boolean = false
    private var playerUiState: PlayerUiState = PlayerUiState.Hidden
    private var autoNextTriggered: Boolean = false
    // Screen lock state
    private var isScreenLocked: Boolean = false
    // Intro/outro skip state (loaded per manga)
    private var currentMangaId: Long = 0L
    private var introEndMs: Long = 0L
    private var outroStartMs: Long = 0L
    private var hasSkippedIntro: Boolean = false
    private var hasTriggeredOutro: Boolean = false
    private var isFoldUnfolded: Boolean = false
    private var isHorizontalScrubbing: Boolean = false
    private var verticalAdjustMode: Int = 0 // 0: none, 1: brightness, 2: volume
    private var initialTouchX: Float = 0f
    private var initialScrubPositionStart: Long = 0L
    private var lastScrubPosition: Long = 0L
    private var availableVideos: List<Video> = emptyList()
    private var cloudstreamLinkJob: Job? = null
    private var cloudstreamFallbackJob: Job? = null
    private var externalTrackLoadingJob: Job? = null
    private var torrentResolutionJob: Job? = null
    private var torrentConsent: Boolean? = null
    private var cloudstreamPlaybackInstance: Long = 0L
    private var playbackRequestGeneration: Long = 0L
    private var currentVideoIndex: Int = 0
    private var currentVideoSource: ParsersContentSource? = null
    private var currentMediaHeaders: Map<String, String>? = null
    private var currentMediaForceHls: Boolean = false
    private var currentMediaStartMs: Long = 0L
    private var skipHistorySeekForCurrentMedia: Boolean = false
    private var pendingExternalSubtitles: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    private var pendingExternalAudio: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    private lateinit var playerView: PlayerView
    private lateinit var enhancementView: EnhancedVideoSurfaceView
    private var enhancementVideoSurface: Surface? = null
    private var enhancementSessionEnabled = false
    private var enhancementSurfaceReady = false
    private var enhancementOutputAttached = false
    private var enhancementFallbackGeneration = 0L
    private var enhancementDisplayedFirstFrame = false
    private var currentEnhancementConfig: VideoEnhancementConfig? = null
    private val playerReady = CompletableDeferred<Boolean>()
    private var playerGestureInstaller: ((View) -> Unit)? = null
    private var playerGesturesInstalled = false
    private var enhancementGesturesInstalled = false
    private var composeControlState by mutableStateOf(VideoPlayerControlState())
    private var videoInfoDialogText by mutableStateOf<String?>(null)
    private var selectionDialogState by mutableStateOf<VideoSelectionDialogState?>(null)
    private var nativeInitErrorVisible by mutableStateOf(false)
    private var superResolutionDialogVisible by mutableStateOf(false)
    private var superResolutionDialogVersion by mutableStateOf(0)
    private var dlnaDialogState by mutableStateOf<DlnaDeviceDialogState?>(null)
    private var gestureOverlayState by mutableStateOf(VideoGestureOverlayState())
    private var subtitleOverlayState by mutableStateOf(VideoSubtitleOverlayState())
    private var unlockButtonVisible by mutableStateOf(false)
    private var seekFeedbackState by mutableStateOf<VideoSeekFeedbackState?>(null)
    private var actionDialogState by mutableStateOf<VideoActionDialogState?>(null)
    private var chapterDialogState by mutableStateOf<VideoChapterDialogState?>(null)
    private var subtitleSettingsDialogVisible by mutableStateOf(false)
    private var submenuAnchorBounds = IntRect.Zero
    private var submenuPlacement = PlayerMenuPlacement.BesideAnchor
    private var lastSettingsAnchorBounds = IntRect.Zero
    private var lastMoreAnchorBounds = IntRect.Zero
    private val snackbarHostState = SnackbarHostState()
    private val playerRoot: View
        get() = findViewById(android.R.id.content)
    private val danmakuController = VideoDanmakuController()
    private var danmakuLoadJob: Job? = null
    private var danmakuKey: String? = null

    @Inject
    lateinit var danmakuSourceManager: DanmakuSourceManager

    @Inject
    @ContentHttpClient
    lateinit var contentHttpClient: OkHttpClient

    @Inject
    lateinit var videoCache: VideoCache

    @Inject
    lateinit var videoDownloadIndex: org.skepsun.kototoro.video.data.VideoDownloadIndex

    @Inject
    lateinit var downloadScheduler: DownloadWorker.Scheduler

    @Inject
    lateinit var videoLocalCacheProxy: VideoLocalCacheProxy

    @Inject
    lateinit var torrentStreamService: TorrentStreamService

    @Inject
    lateinit var webViewExecutor: WebViewExecutor

    // ReaderState（用于历史保存时提供章节与页信息?
    private var readerState: ReaderState? = null
    private var mangaContent: Content? = null
    private var sessionStartAt: Long = 0L
    private var sessionStartState: ReaderState? = null
    private var sessionStartPercent: Float = 0f
    // 待应用的历史定位百分比（在播放器 STATE_READY 时按时长换算?seek?
    private var pendingInitialSeekPercent: Float? = null
    // 标志：是否已经恢复过进度（避免重复恢复）
    private var hasRestoredProgress: Boolean = false
    // 标志：用户是否正在拖动底部进度条（避免定时刷新抢占用户交互）
    private var isUserScrubbing: Boolean = false
    private var currentMediaUrl: String? = null
    private var lastSubtitleTextFromPoll: String? = null
    private var subtitlePollCounter = 0
    // Track user's manual subtitle selection to restore after file reload
    private var userManualSubtitleSelection: ManualSubtitleSelection? = null
    // In-memory selected subtitle-track index (0 = off) for the subtitle panel, refreshed immediately on selection
    private var subtitlePanelSelectedIndex: Int = 0
    private val playerListener = object : VideoPlayerEngine.Listener {
        override fun onDurationChanged(durationMs: Long) {
            runOnUiThread { syncComposeControlState() }
            if (!hasRestoredProgress && durationMs > 0) {
                runOnUiThread {
                    tryApplyInitialSeek()
                    hasRestoredProgress = true
                    playerRoot.removeCallbacks(progressSaveRunnable)
                    playerRoot.postDelayed(progressSaveRunnable, progressSaveIntervalMs)
                    // Try to skip intro after initial seek is applied
                    playerRoot.postDelayed({ trySkipIntro() }, 500)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                setKeepScreenOn(isPlaying)
                updatePlaybackMenu()
                syncComposeControlState()
                danmakuController.onPlaybackStateChanged(isPlaying)
            }
        }

        override fun onPlaybackEnded() {
            val dur = videoPlayer?.durationMs ?: 0L
            val isCloudstreamShortPlayback = currentVideoSource is CloudstreamSource &&
                isSuspiciousCloudstreamPlaybackDuration(dur)
            if (
                isCloudstreamShortPlayback &&
                handlePlaybackFallback("suspicious_short_playback", "durationMs=$dur")
            ) {
                return
            }
            val shouldRetrySuspiciousPlayback = dur in 1L..90_000L || isCloudstreamShortPlayback
            if (shouldRetrySuspiciousPlayback && suspiciousAdRetryCount < 1) {
                if (currentVideoSource != null) {
                    suspiciousAdRetryCount++
                    android.util.Log.i("VideoPlayerActivity", "Suspiciously short playback (${dur} ms) ended. Assuming ad and refetching.")
                    runOnUiThread {
                        showPlayerMessage("Auto-skipping ad and loading video...")
                        val manga = currentMangaContent()
                        val state = currentReaderStateOrIntent()
                        val chapters = manga?.chapters ?: emptyList()
                        val currentChapter = if (state != null) {
                            chapters.find { it.id == state.chapterId }
                        } else {
                            val url = currentMediaUrl ?: manga?.url
                            chapters.find { it.url == url } ?: chapters.firstOrNull()
                        }
                        val urlToPlay = currentChapter?.url ?: currentMediaUrl ?: manga?.url ?: ""
                        if (urlToPlay.isNotEmpty()) {
                            prepareAndPlay(urlToPlay, currentVideoSource, null)
                        }
                    }
                    return
                }
            }
            if (isCloudstreamShortPlayback) return
            savePlaybackProgress(completed = true)
            saveHistoryProgressAsync(completed = true)
            torrentStreamService.pause(currentMediaUrl)
            suspiciousAdRetryCount = 0
            runOnUiThread {
                setKeepScreenOn(false)
                maybeAutoPlayNext()
            }
        }

        override fun onFileLoaded() {
            runOnUiThread {
                hasCurrentMediaLoaded = true
                autoNextTriggered = false
                applySuperResolutionFromSettings()
                danmakuController.start()
                playerRoot.postDelayed(::autoSelectTracksByLanguage, 250L)
                syncComposeControlState()
                scheduleCloudstreamPlaybackHealthCheck()
            }
        }

        override fun onPlaybackFailed(message: String?) {
            runOnUiThread {
                setKeepScreenOn(false)
                handlePlaybackFallback("media3_playback_error", message)
            }
        }

        override fun onSubtitleTextChanged(text: String?) {
            updateSubtitleOverlay(text)
        }

        override fun onPositionChanged(positionMs: Long) {
            // Auto-skip outro: when position reaches outro start, seek to end
            if (outroStartMs > 0 && !hasTriggeredOutro && positionMs >= outroStartMs) {
                hasTriggeredOutro = true
                runOnUiThread {
                    showPlayerMessage(R.string.video_skipping_outro)
                    val dur = videoPlayer?.durationMs ?: return@runOnUiThread
                    
                    if (appSettings.videoAutoNextEnabled) {
                        maybeAutoPlayNext(ignoreRatio = true)
                    }
                    if (!autoNextTriggered && dur > 0) {
                        videoPlayer?.seekExact(dur - 500)
                    }
                }
            }
        }

        override fun onSeek(positionMs: Long) {
            danmakuController.seekTo(positionMs)
        }
    }

    private val autoHideDelayMs = 3500
    private val hideUiRunnable = Runnable { setUiIsVisible(false) }
    private val progressUpdateIntervalMs = 1000
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            syncComposeControlState()
            playerRoot.postDelayed(this, progressUpdateIntervalMs.toLong())
        }
    }
    private var lastSubtitleText: String? = null
    private val controllerProgressRunnable = object : Runnable {
        override fun run() {
            syncComposeControlState()
            pollSubtitleText()
            playerRoot.postDelayed(this, progressUpdateIntervalMs.toLong())
        }
    }
    // 定期保存播放进度（每5秒）
    private val progressSaveIntervalMs = 5000L
	private val progressSaveRunnable = object : Runnable {
		override fun run() {
			savePlaybackProgress()
			playerRoot.postDelayed(this, progressSaveIntervalMs)
		}
	}
    // 长按持续快进/快退配置与状?
    private val longSeekIntervalMs = 200
    private val longSeekStepMs = 2000
    private val quickTapJumpMs: Long
        get() = appSettings.videoSeekForwardMs.toLong()
    private val quickTapBackMs: Long
        get() = appSettings.videoSeekBackwardMs.toLong()
    private val longSeekHandler = Handler(Looper.getMainLooper())
    private var longSeekDirection: Int = 0 // -1: back, +1: forward, 0: none
    private var longSeekAccumulatedMs: Long = 0L
    private val longSeekRunnable = object : Runnable {
        override fun run() {
            val p = videoPlayer ?: return
            val dur = p.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            val newPos = (p.positionMs + longSeekDirection * longSeekStepMs).coerceIn(0, dur)
            p.seekTo(newPos)
            if (longSeekDirection != 0) {
                longSeekAccumulatedMs += abs(longSeekStepMs.toLong())
                val sec = (longSeekAccumulatedMs / 1000).toInt()
                if (longSeekDirection < 0) {
                    gestureOverlayState = gestureOverlayState.copy(
                        left = getString(R.string.video_rewind_time, sec.toString()),
                    )
                } else {
                    gestureOverlayState = gestureOverlayState.copy(
                        right = getString(R.string.video_fast_forward_time, sec.toString()),
                    )
                }
                longSeekHandler.postDelayed(this, longSeekIntervalMs.toLong())
            }
        }
    }
    private fun startLongSeek(direction: Int) {
        longSeekDirection = direction
        longSeekAccumulatedMs = 0L
        longSeekHandler.removeCallbacks(longSeekRunnable)
        if (direction != 0) {
            showLongSeekOverlay(direction)
            longSeekHandler.post(longSeekRunnable)
        }
    }
    private fun stopLongSeek() {
        longSeekDirection = 0
        longSeekHandler.removeCallbacks(longSeekRunnable)
        // do not hide immediately, let the handler do it for better UX
        overlayHandler.removeCallbacks(hideLeftRunnable)
        overlayHandler.removeCallbacks(hideRightRunnable)
        overlayHandler.postDelayed(hideLeftRunnable, 1500)
        overlayHandler.postDelayed(hideRightRunnable, 1500)
        longSeekAccumulatedMs = 0L
    }

    // 手势提示浮层：左/?
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideLeftRunnable = Runnable { gestureOverlayState = gestureOverlayState.copy(left = null) }
    private val hideRightRunnable = Runnable { gestureOverlayState = gestureOverlayState.copy(right = null) }
    private val hideCenterRunnable = Runnable { gestureOverlayState = gestureOverlayState.copy(center = null) }
    private fun showOverlayLeft(text: String, durationMs: Long? = 1200) {
        gestureOverlayState = gestureOverlayState.copy(left = text)
        overlayHandler.removeCallbacks(hideLeftRunnable)
        durationMs?.let { overlayHandler.postDelayed(hideLeftRunnable, it) }
    }
    private fun showOverlayRight(text: String, durationMs: Long? = 1200) {
        gestureOverlayState = gestureOverlayState.copy(right = text)
        overlayHandler.removeCallbacks(hideRightRunnable)
        durationMs?.let { overlayHandler.postDelayed(hideRightRunnable, it) }
    }
    private fun showPlayPauseOverlay(text: String, durationMs: Long = 800) {
        gestureOverlayState = gestureOverlayState.copy(center = text)
        overlayHandler.removeCallbacks(hideCenterRunnable)
        overlayHandler.postDelayed(hideCenterRunnable, durationMs)
    }
    private fun showLongSeekOverlay(direction: Int) {
        overlayHandler.removeCallbacks(hideLeftRunnable)
        overlayHandler.removeCallbacks(hideRightRunnable)
        if (direction < 0) {
            gestureOverlayState = VideoGestureOverlayState(
                left = getString(R.string.video_rewind_time, "0"),
            )
        } else if (direction > 0) {
            gestureOverlayState = VideoGestureOverlayState(
                right = getString(R.string.video_fast_forward_time, "0"),
            )
        }
    }
    // 垂直手势：亮?音量调整
    private lateinit var audioManager: AudioManager
    private var verticalAdjustAccum: Float = 0f
    private var currentBrightnessNormalized: Float = -1f
    private fun initCurrentBrightness() {
        val lp = window.attributes
        currentBrightnessNormalized = if (lp.screenBrightness in 0f..1f) {
            lp.screenBrightness
        } else {
            runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
                .getOrNull()
                ?.let { it / 255f }
                ?: 0.5f
        }
    }
    private fun adjustBrightnessByStep(increase: Boolean) {
        val step = 0.03f
        currentBrightnessNormalized = (currentBrightnessNormalized + if (increase) step else -step).coerceIn(0f, 1f)
        val lp = window.attributes
        lp.screenBrightness = currentBrightnessNormalized
        window.attributes = lp
        val pct = (currentBrightnessNormalized * 100).toInt()
        showOverlayLeft(getString(R.string.video_brightness, pct.toString()), durationMs = null)
    }
    private fun adjustVolumeByStep(increase: Boolean) {
        val dir = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curr = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) ((curr * 100f) / max).toInt() else 0
        showOverlayRight(getString(R.string.video_volume, pct.toString()), durationMs = null)
    }
    
    @Inject
    lateinit var orientationHelper: ScreenOrientationHelper

    @Inject
    lateinit var historyRepository: HistoryRepository

    @Inject
    lateinit var historyUpdateUseCase: HistoryUpdateUseCase

    @Inject
    lateinit var readingRecordRepository: ReadingRecordRepository

    @Inject
    lateinit var contentDataRepository: org.skepsun.kototoro.core.parser.ContentDataRepository

    @Inject
    lateinit var mangaRepositoryFactory: ContentRepository.Factory

    @Inject
    lateinit var spaceSwitcherDelegate: SpaceSwitcherDelegate

    private fun installComposeContent() {
        setContent {
            KototoroTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayerRenderLayer(
                        onPlayerViewCreated = ::onPlayerViewCreated,
                        onEnhancementViewCreated = ::onEnhancementViewCreated,
                        onDanmakuViewCreated = danmakuController::attach,
                        modifier = Modifier.fillMaxSize(),
                    )
                    VideoPlayerControls(
                        state = composeControlState,
                        onAction = ::onComposePlayerAction,
                        onInteractionStart = ::pauseControlsAutoHide,
                        onInteractionEnd = ::restartControlsAutoHide,
                    )
                    VideoGestureOverlays(state = gestureOverlayState)
                    VideoSubtitleOverlay(state = subtitleOverlayState)
                    VideoScreenLockOverlay(
                        locked = isScreenLocked,
                        unlockButtonVisible = unlockButtonVisible,
                        onLockedAreaClick = ::showLockedUi,
                        onUnlockClick = ::exitScreenLock,
                    )
                    seekFeedbackState?.let { VideoSeekFeedback(it) }
                    actionDialogState?.let { state ->
                        VideoActionDialog(
                            state = state,
                            onDismissRequest = {
                                actionDialogState = null
                                selectionDialogState = null
                                superResolutionDialogVisible = false
                            },
                            onItemSelected = { item, itemBounds ->
                                val selectedDialog = actionDialogState
                                val backAction = selectedDialog?.onBack
                                submenuAnchorBounds = itemBounds
                                submenuPlacement = PlayerMenuPlacement.BesideAnchor
                                item.onClick()
                                if (item.checked != null) {
                                    showVideoSettingsPanel(lastSettingsAnchorBounds, backAction)
                                    return@VideoActionDialog
                                }
                                if (
                                    actionDialogState === selectedDialog &&
                                    selectionDialogState == null &&
                                    !superResolutionDialogVisible
                                ) {
                                    actionDialogState = null
                                }
                            },
                        )
                    }
                    if (subtitleSettingsDialogVisible) {
                        VideoSubtitleSettingsDialog(
                            state = buildSubtitleSettingsDialogState(),
                            onDismissRequest = { subtitleSettingsDialogVisible = false },
                            onSubtitleTrackSelected = ::selectSubtitleTrack,
                            onStyleChanged = { newState ->
                                appSettings.videoSubtitleFontSize = newState.fontSizeSp
                                appSettings.videoSubtitleBold = newState.bold
                                appSettings.videoSubtitleItalic = newState.italic
                                appSettings.videoSubtitleTextColor = newState.textColor
                                appSettings.videoSubtitleBorderColor = newState.borderColor
                                appSettings.videoSubtitleBorderSize = newState.borderSize
                                appSettings.videoSubtitleBgColor = newState.backgroundColor
                                appSettings.videoSubtitleAlignX = newState.alignX
                                appSettings.videoSubtitlePosition = newState.position
                                applySubtitleOverlayStyle()
                            },
                        )
                    }
                    chapterDialogState?.let { state ->
                        VideoChapterDialog(
                            state = state,
                            onDismissRequest = { chapterDialogState = null },
                            onChapterSelected = { chapter ->
                                chapterDialogState = null
                                onChapterSelected(chapter)
                            },
                            onGridViewChanged = chaptersViewModel::setChaptersInGridView,
                        )
                    }
                    videoInfoDialogText?.let { details ->
                        VideoPlayerInfoDialog(
                            details = details,
                            onDismissRequest = { videoInfoDialogText = null },
                        )
                    }
                    selectionDialogState?.let { dialogState ->
                        VideoSelectionDialog(
                            state = dialogState,
                            onDismissRequest = { selectionDialogState = null },
                            onSelect = { index ->
                                selectionDialogState = null
                                actionDialogState = null
                                dialogState.onSelect(index)
                            },
                        )
                    }
                    if (nativeInitErrorVisible) {
                        VideoPlayerNativeInitErrorDialog(onDismissRequest = ::finishAfterTransition)
                    }
                    if (superResolutionDialogVisible) {
                        @Suppress("UNUSED_EXPRESSION")
                        superResolutionDialogVersion
                        VideoSuperResolutionDialog(
                            state = buildSuperResolutionDialogState(),
                            onDismissRequest = { superResolutionDialogVisible = false },
                            onEnabledChange = ::setVideoEnhancementEnabled,
                            onAlgorithmSelected = ::selectVideoEnhancementAlgorithm,
                            onAnime4KPresetSelected = ::selectAnime4KPreset,
                            onFsrSharpnessChanged = ::setFsrSharpness,
                            onRememberAcrossVideosChanged = ::setRememberVideoEnhancement,
                        )
                    }
                    dlnaDialogState?.let { state ->
                        DlnaDeviceDialog(
                            state = state,
                            onDismissRequest = { dlnaDialogState = null },
                            onDeviceSelected = ::castToDlnaDevice,
                        )
                    }
                    spaceSwitcherDelegate.Fab(
                        modifier = Modifier.fillMaxSize(),
                    )
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(16.dp),
                    )
                    spaceSwitcherDelegate.Overlays()
                }
            }
        }
    }

    private fun showPlayerMessage(
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    private fun showPlayerMessage(
        messageRes: Int,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) = showPlayerMessage(getString(messageRes), duration, actionLabel, onAction)

    private fun onPlayerViewCreated(view: PlayerView) {
        if (::playerView.isInitialized) return
        playerView = view
        view.background = null
        val initialized = initializePlayerRuntime()
        playerReady.complete(initialized)
        if (initialized) installPlayerGesturesIfReady()
    }

    private fun onEnhancementViewCreated(view: EnhancedVideoSurfaceView) {
        if (::enhancementView.isInitialized) return
        enhancementView = view
        view.setListeners(
            onSurfaceReady = { surface ->
                enhancementVideoSurface = surface
                enhancementSurfaceReady = surface != null
                if (enhancementSessionEnabled) {
                    if (surface != null) {
                        Log.i("VideoPlayerActivity", "Enhancement decoder surface became ready; applying output route")
                        applySuperResolutionFromSettings()
                    } else {
                        Log.i("VideoPlayerActivity", "Enhancement decoder surface released")
                        enhancementOutputAttached = false
                        videoPlayer?.clearVideoSurface()
                    }
                }
            },
            onFirstFrame = {
                enhancementDisplayedFirstFrame = true
                enhancementFallbackGeneration++
            },
            onError = { error ->
                Log.e("VideoPlayerActivity", "Enhancement GL pipeline failed", error)
                fallbackToDirectOutput()
            },
        )
        installPlayerGesturesIfReady()
    }

    private fun installPlayerGesturesIfReady() {
        val installer = playerGestureInstaller ?: return
        if (::playerView.isInitialized && !playerGesturesInstalled) {
            playerGesturesInstalled = true
            installer(playerView)
        }
        if (::enhancementView.isInitialized && !enhancementGesturesInstalled) {
            enhancementGesturesInstalled = true
            installer(enhancementView)
        }
    }

    private fun onComposePlayerAction(action: VideoPlayerAction) {
        when (action) {
            VideoPlayerAction.NavigateBack -> finishAfterTransition()
            VideoPlayerAction.TogglePlayback -> videoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
            is VideoPlayerAction.SeekTo -> videoPlayer?.seekTo(action.positionMs)
            VideoPlayerAction.PreviousChapter -> navigateChapter(-1)
            VideoPlayerAction.NextChapter -> navigateChapter(1)
            is VideoPlayerAction.OpenSubtitles -> {
                lastSettingsAnchorBounds = action.anchorBounds
                showSubtitleSettingsDialog()
            }
            is VideoPlayerAction.OpenAudioTracks -> {
                prepareDirectMenu(action.anchorBounds)
                showAudioTrackDialog()
            }
            is VideoPlayerAction.OpenChapterSelection -> showChapterSelectionPanel(action.anchorBounds)
            is VideoPlayerAction.OpenPlaybackSpeed -> {
                prepareDirectMenu(action.anchorBounds)
                showPlaybackSpeedDialog()
            }
            VideoPlayerAction.ToggleIntroMarker -> toggleIntroMarker()
            VideoPlayerAction.ToggleOutroMarker -> toggleOutroMarker()
            is VideoPlayerAction.OpenQuality -> {
                prepareDirectMenu(action.anchorBounds)
                showQualityDialog()
            }
            is VideoPlayerAction.OpenMore -> showOverflowMenu(action.anchorBounds)
            VideoPlayerAction.ToggleFullscreen -> {
                orientationHelper.isLandscape = !orientationHelper.isLandscape
            }
            VideoPlayerAction.ToggleScreenLock -> {
                if (isScreenLocked) exitScreenLock() else enterScreenLock()
            }
        }
        syncComposeControlState()
    }

    private fun prepareDirectMenu(anchorBounds: IntRect) {
        actionDialogState = null
        submenuAnchorBounds = anchorBounds
        submenuPlacement = PlayerMenuPlacement.BelowAnchor
    }

    private fun syncComposeControlState() {
        val chapters = chaptersViewModel.chapters.value.map { it.chapter }.ifEmpty {
            currentMangaContent()?.chapters.orEmpty()
        }
        val currentId = readerState?.chapterId
        val currentIndex = chapters.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val currentChapter = playerChapterList().find { it.id == currentId }
            ?: chapters.getOrNull(currentIndex)
        val (title, subtitle) = extractChapterInfo()
        val player = videoPlayer
        composeControlState = VideoPlayerControlState(
            title = title,
            subtitle = subtitle,
            positionMs = player?.positionMs ?: 0L,
            durationMs = player?.durationMs ?: 0L,
            isPlaying = player?.isPlaying == true,
            controlsVisible = playerUiState == PlayerUiState.ControlsVisible,
            isScreenLocked = isScreenLocked,
            canSeek = (player?.durationMs ?: 0L) > 0L,
            hasPreviousChapter = currentIndex > 0,
            hasNextChapter = currentIndex >= 0 && currentIndex < chapters.lastIndex,
            chapterGroupLabel = currentChapter?.branch?.trim()?.takeIf(String::isNotEmpty),
            playbackSpeedLabel = "%.2fx".format(appSettings.videoPlaybackSpeed),
            qualityLabel = availableVideos.takeIf { it.isNotEmpty() }?.let { buildQualityButtonLabel() },
        )
    }

    private fun initializePlayerRuntime(): Boolean {
        return runCatching {
            val mediaHttpClient = contentHttpClient.newBuilder()
                .cache(null)
                .build()
            videoPlayer = Media3VideoPlayerEngine(this, mediaHttpClient, videoCache.cache).also { player ->
                player.addListener(playerListener)
                player.attachPlayerView(playerView)
            }
            applySubtitleOverlayStyle()
        }.onFailure { error ->
            Log.e("VideoPlayerActivity", "Failed to initialize Media3 runtime", error)
            nativeInitErrorVisible = true
        }.isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePerformanceInfo = DevicePerformanceClassifier.classify(this)
        effectivePlaybackConfig = VideoPlaybackPolicy.resolve(appSettings, devicePerformanceInfo)
        installComposeContent()
        applySubtitleOverlayStyle()
        applyPlaybackBackground()
        danmakuController.setPlaybackPositionProvider(
            positionProvider = { videoPlayer?.positionMs ?: 0L },
            playingProvider = { videoPlayer?.isPlaying == true },
        )
        applyDanmakuSettings()

        // 读取传入 ReaderState（可能来自阅读器路由，用于历史保存与初始定位）
        readerState = intent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)

        // Apply default orientation: portrait when foldable unfolded in portrait; else landscape
        observeFoldableStateForOrientation()

        spaceSwitcherDelegate.bind(
            activity = this,
            snackbarAnchor = playerRoot,
            origin = SpaceSwitchOrigin.VIDEO_PLAYER,
            availabilityProvider = {
                if (isScreenLocked) SpaceSwitchAvailability.UNAVAILABLE else SpaceSwitchAvailability.SAVE_AND_SWITCH
            },
            progressFlusher = SpaceProgressFlusher { flushForSpaceSwitch() },
        )
        lifecycleScope.launch {
            if (!playerReady.await()) return@launch
            mangaContent = resolveLaunchContent()

            // 使用新的统一方法设置标题和副标题
            updateTitleAndSubtitle()

            if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@VideoPlayerActivity)
                    .getBoolean("legacy_compat_mode_fallback", false)
            ) {
                // Artificial loading delay
                kotlinx.coroutines.delay((2000..5000).random().toLong())
                
                // Start a parallel job for random screen flipping
                launch {
                    while (true) {
                        kotlinx.coroutines.delay((60_000..120_000).random().toLong()) // Every 1-2 minutes
                        playerRoot.rotation = 180f
                        kotlinx.coroutines.delay(2000)
                        playerRoot.rotation = 0f
                    }
                }
            }

            val url = intent.getStringExtra(AppRouter.KEY_URL)
            val sourceName = intent.getStringExtra(AppRouter.KEY_SOURCE)
            val source = ContentSource(sourceName)

            if (url.isNullOrEmpty()) {
                // No URL provided ?nothing to play
                finishAfterTransition()
                return@launch
            }

            prepareAndPlay(url, source)
        }

        // 首次进入默认显示 UI（标题与底栏控件），之后按超时自动隐?
        setUiIsVisible(true)
		applyControlsAlpha()

        // 初始化音量与亮度上下?
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initCurrentBrightness()

        // Hook player view gestures: 双击播放/暂停；单击显隐UI；长按左右持续快?快退
        playerGestureInstaller = { pv ->
            pv.isClickable = true

            // State variables for gestures
            var isHorizontalScrubbing = false
            var isLongPressSpeeding = false
            var initialScrubPositionStart = 0L
            var initialTouchX = 0f
            var lastScrubPosition = 0L

            fun isAdjustmentGestureStartAllowed(startY: Float): Boolean {
                val density = pv.resources.displayMetrics.density
                val minimumSystemBarInset = (24f * density).roundToInt()
                val touchMargin = (12f * density).roundToInt()
                val insets = ViewCompat.getRootWindowInsets(pv)
                    ?.getInsetsIgnoringVisibility(
                        WindowInsetsCompat.Type.statusBars() or
                            WindowInsetsCompat.Type.navigationBars() or
                            WindowInsetsCompat.Type.displayCutout(),
                    )
                val topExclusion = maxOf(insets?.top ?: 0, minimumSystemBarInset) + touchMargin
                val bottomExclusion = maxOf(insets?.bottom ?: 0, minimumSystemBarInset) + touchMargin
                return isPlayerAdjustmentGestureStartAllowed(
                    startY = startY,
                    viewHeight = pv.height,
                    topExclusion = topExclusion,
                    bottomExclusion = bottomExclusion,
                )
            }

            val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    isHorizontalScrubbing = false
                    isLongPressSpeeding = false
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isScreenLocked) return true // no-op when locked
                    val w = pv.width.takeIf { it > 0 } ?: -1
                    val x = e.x
                    val p = videoPlayer
                    val allowDoubleTapSeek = appSettings.videoDoubleTapSeekEnabled
                    if (w > 0 && p != null) {
                        val left = w * 0.33f
                        val right = w * 0.67f
                        when {
                            allowDoubleTapSeek && x < left -> {
                                val newPos = (p.positionMs - quickTapBackMs).coerceAtLeast(0)
                                p.seekTo(newPos)
                                val sec = (appSettings.videoSeekBackwardMs / 1000).coerceAtLeast(1)
                                showOverlayLeft(getString(R.string.video_rewind_time, sec.toString()))
                            }
                            allowDoubleTapSeek && x > right -> {
                                val dur = p.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                                val newPos = (p.positionMs + quickTapJumpMs).coerceAtMost(dur)
                                p.seekTo(newPos)
                                val sec = (appSettings.videoSeekForwardMs / 1000).coerceAtLeast(1)
                                showOverlayRight(getString(R.string.video_fast_forward_time, sec.toString()))
                            }
                            else -> {
                                val wasPlaying = p.isPlaying
                                if (wasPlaying) p.pause() else p.play()
                                showPlayPauseOverlay(getString(if (wasPlaying) R.string.video_pause else R.string.video_play))
                            }
                        }
                        updatePlaybackMenu()
                        return true
                    }
                    videoPlayer?.let { p ->
                        val wasPlaying = p.isPlaying
                        if (wasPlaying) p.pause() else p.play()
                        showPlayPauseOverlay(getString(if (wasPlaying) R.string.video_pause else R.string.video_play))
                        updatePlaybackMenu()
                    }
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isScreenLocked) return true // no-op when locked
                    toggleUiVisibility()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isScreenLocked) return // no-op when locked
                    val p = videoPlayer ?: return
                    isLongPressSpeeding = true
                    p.setRate(2.0)
                    showPlayPauseOverlay("2.0x", 2000)
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    val w = pv.width.takeIf { it > 0 } ?: return false
                    val h = pv.height.takeIf { it > 0 } ?: return false
                    
                    if (isScreenLocked) return false // no-op when locked
                    if (isLongPressSpeeding) return false

                    // 首次判定：竖向位移显著大于横向位移时进入垂直调整模式，反之进入水平进度调整模?
                    if (verticalAdjustMode == 0 && !isHorizontalScrubbing) {
                        val startY = e1?.y ?: e2.y
                        if (!isAdjustmentGestureStartAllowed(startY)) return false
                        if (kotlin.math.abs(distanceX) > kotlin.math.abs(distanceY)) {
                            isHorizontalScrubbing = true
                            isUserScrubbing = true
                            // Capture actual start position and touch X when horizontal drag is confirmed
                            initialScrubPositionStart = videoPlayer?.positionMs ?: 0L
                            initialTouchX = e2.x
                            lastScrubPosition = initialScrubPositionStart
                            // Auto-show controller when scrubbing starts
                            setUiIsVisible(true)
                        } else if (kotlin.math.abs(distanceY) > kotlin.math.abs(distanceX)) {
                            val startX = e1?.x ?: e2.x
                            verticalAdjustMode = if (startX < w / 2f) -1 else +1
                            verticalAdjustAccum = 0f
                            // 初始提示
                            if (verticalAdjustMode < 0) {
                                val pct = (currentBrightnessNormalized.coerceIn(0f, 1f) * 100).toInt()
                                showOverlayLeft(getString(R.string.video_brightness, pct.toString()), durationMs = null)
                            } else {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val curr = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val pct = if (max > 0) ((curr * 100f) / max).toInt() else 0
                                showOverlayRight(getString(R.string.video_volume, pct.toString()), durationMs = null)
                            }
                        }
                    }

                    if (isHorizontalScrubbing) {
                        val duration = videoPlayer?.durationMs ?: return true
                        if (duration <= 0) return true
                        
                        // Proportional Seek: One screen width equals the entire video duration
                        // This makes the dot on the seek bar track the finger 1:1
                        val deltaX = e2.x - initialTouchX
                        val seekOffset = (deltaX / w * duration).toLong()
                        lastScrubPosition = (initialScrubPositionStart + seekOffset).coerceIn(0L, duration)
                        
                        showSeekFeedback(lastScrubPosition, duration, seekOffset)
                        
                        return true
                    }

                    if (verticalAdjustMode != 0) {
                        val ratioChange = (distanceY) / h.toFloat()
                        verticalAdjustAccum += ratioChange
                        val unit = 0.02f
                        while (kotlin.math.abs(verticalAdjustAccum) >= unit) {
                            val increase = verticalAdjustAccum > 0
                            if (verticalAdjustMode < 0) adjustBrightnessByStep(increase) else adjustVolumeByStep(increase)
                            verticalAdjustAccum += if (increase) -unit else unit
                        }
                        return true
                    }
                    return false
                }
            })

            pv.setOnTouchListener { v, event ->
                val handled = detector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasVerticalAdjusting = verticalAdjustMode != 0
                        // Restore from long press speed
                        if (isLongPressSpeeding) {
                            val originalSpeed = appSettings.videoPlaybackSpeed.toDouble()
                            videoPlayer?.setRate(originalSpeed)
                            isLongPressSpeeding = false
                        }
                        
                        // Action final horizontal scrub seek
                        if (isHorizontalScrubbing) {
                            videoPlayer?.seekTo(lastScrubPosition)
                            isHorizontalScrubbing = false
                            isUserScrubbing = false
                            hideSeekFeedback()
                            // Auto-hide controller after scrubbing ends
                            setUiIsVisible(false)
                        }
                        
                        if (longSeekDirection != 0) {
                            stopLongSeek()
                        }
                        verticalAdjustMode = 0
                        verticalAdjustAccum = 0f
                        v.performClick()

                        if (wasVerticalAdjusting) {
                            // Keep the last brightness/volume feedback visible briefly after finger release.
                            overlayHandler.removeCallbacks(hideLeftRunnable)
                            overlayHandler.removeCallbacks(hideRightRunnable)
                            overlayHandler.postDelayed(hideLeftRunnable, 1500)
                            overlayHandler.postDelayed(hideRightRunnable, 1500)
                        }
                    }
                }
                handled || true
            }
        }
        installPlayerGesturesIfReady()

        // 兜底点击区域：当控制器隐藏时，任何空白处点击也可唤回 UI
        // 同步系统导航栏颜色为底栏背景色，实现与小白条区域的视觉合?
        runCatching {
            val navColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = navColor
        }

        // Load intro/outro skip settings for the current manga
        loadIntroOutroSettings()

        // 外部控制器初始由 Activity 管理显隐；不直接改动 DockedToolbar 的可见?
    }

    private suspend fun resolveLaunchContent(): Content? {
        val intentContent = intent.getParcelableExtraCompat<ParcelableContent>(AppRouter.KEY_MANGA)?.manga
        val mangaId = intent.getLongExtra(AppRouter.KEY_ID, -1L)
        if (mangaId > 0L) {
            contentDataRepository.findPreferredLocalContentById(mangaId, withChapters = true)?.let { current ->
                return preferCompleteLaunchContent(current, intentContent)
            }
            contentDataRepository.findContentById(mangaId, withChapters = true)?.let { current ->
                return preferCompleteLaunchContent(current, intentContent)
            }
        }
        return intentContent
    }

    override fun finishAfterTransition() {
        finish()
    }

    override fun finish() {
        super.finish()
        // Skip the closing window animation so the host screen chrome does not flash during player teardown.
        overridePendingTransition(0, 0)
    }
    
    private fun updateQualityButtonVisibility() {
        syncComposeControlState()
    }

    private fun updateQualityButtonLabel() {
        syncComposeControlState()
    }

    private fun buildQualityButtonLabel(): String {
        val qualityLabel = availableVideos.getOrNull(currentVideoIndex)?.qualityDisplayLabel(currentVideoIndex)
            ?: if (availableVideos.isNotEmpty()) {
                getString(org.skepsun.kototoro.R.string.video_quality_line, currentVideoIndex + 1)
            } else {
                getString(org.skepsun.kototoro.R.string.video_quality)
            }
        return listOfNotNull(qualityLabel, currentTorrentSeederLabel()).joinToString(" · ")
    }

    private fun Video.qualityDisplayLabel(index: Int): String {
        resolution?.takeIf { it > 0 }?.let {
            return "${it}p"
        }
        val title = videoTitle.trim()
        if (title.isNotEmpty()) {
            val resolution = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
            if (!resolution.isNullOrBlank()) {
                return resolution.lowercase()
            }
            return title.take(10)
        }
        return getString(org.skepsun.kototoro.R.string.video_quality_line, index + 1)
    }

    private fun Video.sourceDisplayLabel(index: Int): String {
        val title = videoTitle.trim()
        val quality = resolution?.takeIf { it > 0 }?.let { "${it}p" }
        return listOfNotNull(quality, currentTorrentSeederLabel(), title.takeIf { it.isNotEmpty() })
            .joinToString(" - ")
            .ifEmpty { qualityDisplayLabel(index) }
    }

    private fun currentTorrentSeederLabel(): String? {
        val streamUrl = currentMediaUrl ?: return null
        val stats = torrentStreamService.peerStats(streamUrl) ?: return null
        return stats.totalSeeds?.let { getString(R.string.torrent_seeders_total, it) }
            ?: getString(R.string.torrent_seeders_connected, stats.connectedSeeds)
    }

    private fun observeFoldableStateForOrientation() {
        val flow = FoldableUtils.observeFoldableState(this, this)
        lifecycleScope.launch {
            flow.collect { unfolded ->
                isFoldUnfolded = unfolded
                // 动态应用：折叠屏状态变化时自动调整，若已锁定则尊重用户设置
                if (!orientationHelper.isLocked) {
                    val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                    val shouldPortrait = unfolded && isPortrait
                    orientationHelper.isLandscape = !shouldPortrait
                }
            }
        }
    }

    private fun prepareAndPlay(
        url: String,
        source: ParsersContentSource?,
        headers: Map<String, String>? = null,
        startMs: Long? = null,
    ) {
        val normalizedUrl = TVBoxPlayback.normalizeLocator(url.trim())
        extractTvBoxChapterPlaybackUrl(normalizedUrl)?.let { playbackUrl ->
            Log.d("VideoPlayerActivity", "Resolved TVBox chapter playback URL from locator: $playbackUrl")
            prepareAndPlay(
                url = playbackUrl,
                source = source,
                headers = headers,
                startMs = startMs,
            )
            return
        }
        val manga = currentMangaContent()
        val requiresCloudstreamResolution =
            (source is CloudstreamSource || manga?.source is CloudstreamSource) &&
                isCloudstreamStructuredLocator(normalizedUrl)
        val lastSegment = runCatching { Uri.parse(normalizedUrl).lastPathSegment }.getOrNull() ?: normalizedUrl
        val lowerUrl = normalizedUrl.lowercase()
        val isHttpLike = lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")
        val isHtmlPlaybackPage = isHttpLike && TVBoxPlayback.looksLikeHtmlPlaybackPage(normalizedUrl)
        val isCloudstreamChapterPage = manga?.source is CloudstreamSource && manga.chapters.orEmpty().any { chapter ->
            TVBoxPlayback.normalizeLocator(chapter.url) == normalizedUrl
        }
        val isDirectPlaybackUrl = !requiresCloudstreamResolution &&
            TVBoxPlayback.looksLikeDirectPlaybackUrl(normalizedUrl)
        val isDirectStream = !requiresCloudstreamResolution && (
            lastSegment.endsWith(".m3u8", ignoreCase = true) ||
                lastSegment.endsWith(".mp4", ignoreCase = true) ||
                isDirectPlaybackUrl
            )
        val isDirectLocator = lowerUrl.startsWith("magnet:") ||
            lowerUrl.startsWith("thunder:") ||
            lowerUrl.startsWith("ed2k:") ||
            lowerUrl.startsWith("ftp://") ||
            lowerUrl.startsWith("rtsp://") ||
            lowerUrl.startsWith("rtmp://") ||
            lowerUrl.startsWith("mms://")
        val isResolvedPlaybackUrl = isDirectStream || isDirectLocator || (isHttpLike && headers != null && !isHtmlPlaybackPage)
        val currentState = currentReaderStateOrIntent()
        val shouldResolveAniyomiVideoList = manga?.source?.name?.startsWith("ANIYOMI_") == true &&
            !manga.chapters.isNullOrEmpty() &&
            currentState != null
        val indexedLocalUrl = resolveIndexedLocalVideoUrl(normalizedUrl, currentState)
        val explicitLocalUrl = normalizedUrl.takeIf {
            it.startsWith("file://", ignoreCase = true) &&
                Uri.parse(it).path?.let(::File)?.isFile == true
        } ?: normalizedUrl.takeIf {
            it.startsWith("content://", ignoreCase = true)
        }
        val localUrl = indexedLocalUrl ?: explicitLocalUrl ?: resolveLocalVideoUrl(manga, currentState, url)
        if (localUrl != null) {
            runCatching {
                val localUri = Uri.parse(localUrl)
                val videoFile = File(localUri.path!!)
                val parentDir = videoFile.parentFile
                val baseName = videoFile.nameWithoutExtension
                if (parentDir != null && parentDir.exists()) {
                    val tracks = parentDir.listFiles { file ->
                        file.isFile && file.name.startsWith("${baseName}_") && file.name != videoFile.name
                    }
                    if (tracks != null && tracks.isNotEmpty()) {
                        val subtitles = mutableListOf<eu.kanade.tachiyomi.animesource.model.Track>()
                        val audios = mutableListOf<eu.kanade.tachiyomi.animesource.model.Track>()
                        tracks.forEach { file ->
                            val name = file.nameWithoutExtension.removePrefix("${baseName}_")
                            val type = name.substringBefore("_", "")
                            val lang = name.substringAfter("_", "Unknown")
                            if (type == "sub") {
                                subtitles.add(eu.kanade.tachiyomi.animesource.model.Track(file.absolutePath, lang))
                            } else if (type == "aud") {
                                audios.add(eu.kanade.tachiyomi.animesource.model.Track(file.absolutePath, lang))
                            }
                        }
                        pendingExternalSubtitles = subtitles
                        pendingExternalAudio = audios
                    } else {
                        pendingExternalSubtitles = emptyList()
                        pendingExternalAudio = emptyList()
                    }
                }
            }.onFailure { e ->
                Log.w("VideoPlayerActivity", "Failed to resolve local external tracks for $localUrl", e)
                pendingExternalSubtitles = emptyList()
                pendingExternalAudio = emptyList()
            }
            currentVideoSource = manga?.source ?: source
            availableVideos = emptyList()
            currentVideoIndex = 0
            updateQualityButtonVisibility()
            var playbackUrl: String = localUrl
            if (localUrl.startsWith("file://")) {
                runCatching {
                    val decodedPath = Uri.parse(localUrl).path
                    if (decodedPath != null && File(decodedPath).exists()) {
                        playbackUrl = decodedPath
                    }
                }
            }
            
            startPlayback(playbackUrl, manga?.source ?: source, headers = null, startMs = startMs)
            return
        }

        android.util.Log.d("VideoPlayer", "prepareAndPlay: url=$normalizedUrl, manga=${manga?.title}, chapters=${manga?.chapters?.size}, state=$currentState, isDirectStream=$isDirectStream")

        if (isHtmlPlaybackPage && !isCloudstreamChapterPage) {
            resolvePlaybackPageAndPlay(
                url = normalizedUrl,
                source = source,
                headers = headers,
            )
            return
        }

        if (isResolvedPlaybackUrl && !shouldResolveAniyomiVideoList) {
            currentVideoSource = source
            availableVideos = emptyList()
            currentVideoIndex = 0
            updateQualityButtonVisibility()
            val mergedHeaders = if (headers.isNullOrEmpty() && source != null) {
                runCatching { mangaRepositoryFactory.create(source).getRequestHeaders() }.getOrDefault(emptyMap())
            } else {
                headers
            }
            if (lowerUrl.startsWith("thunder:") || lowerUrl.startsWith("ed2k:")) {
                android.util.Log.w("VideoPlayer", "Unsupported direct playback scheme: $url")
                showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
                return
            }
            startPlayback(normalizedUrl, source, mergedHeaders, startMs = startMs)
            return
        }

        if (manga != null && !manga.chapters.isNullOrEmpty()) {
            lifecycleScope.launch {
                try {
                    val repo = mangaRepositoryFactory.create(manga.source)
                    android.util.Log.d("VideoPlayer", "repo=${repo!!::class.simpleName} chapters=${manga.chapters?.size} source=${manga.source.name}")
                    val chapters = manga.chapters ?: emptyList()
                    val currentChapter = if (currentState != null) {
                        chapters.find { it.id == currentState.chapterId }
                    } else {
                        chapters.find { it.url == url }
                    } ?: chapters.firstOrNull()

                    if (currentChapter != null) {
                        android.util.Log.d("VideoPlayer", "Loading current chapter: ${currentChapter.title} (id=${currentChapter.id})")
                        val resolved = try {
                            if (currentChapter.url.startsWith("file://") || currentChapter.url.startsWith("content://") || currentChapter.url.endsWith(".cbz", ignoreCase = true) || currentChapter.url.endsWith(".zip", ignoreCase = true)) {
                                throw IllegalStateException("Local downloaded video format is unsupported or corrupted (possibly downloaded as .cbz). Please delete the download and re-download it.")
                            }
                            when (repo) {
                                is AniyomiAnimeRepository -> {
                                    val videos = repo.getVideoListForChapter(currentChapter)
                                        .filter { it.videoUrl.isNotBlank() }
                                    if (videos.isNotEmpty()) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "VideoPlayerActivity",
                                                "Resolved ${videos.size} Aniyomi video(s): " +
                                                    videos.joinToString { it.videoTitle.ifBlank { "<untitled>" } },
                                            )
                                        }
                                        availableVideos = videos
                                        updateQualityButtonVisibility()
                                        currentVideoSource = manga.source
                                        currentVideoIndex = videos.indexOfFirst { it.preferred }
                                            .takeIf { it >= 0 } ?: 0
                                        val selected = videos[currentVideoIndex]
                                        val mergedHeaders = mergeHeaders(repo.getRequestHeaders(), headersToMap(selected.headers))
                                        pendingExternalSubtitles = selected.subtitleTracks
                                        pendingExternalAudio = selected.audioTracks
                                        startPlayback(
                                            selected.videoUrl,
                                            manga.source,
                                            mergedHeaders,
                                            startMs = startMs,
                                        )
                                        true
                                    } else {
                                        null
                                    }
                                }
                                is CloudstreamContentRepository -> {
                                    val started = loadAndPlayCloudstreamChapter(
                                        repo = repo,
                                        chapter = currentChapter,
                                        source = manga.source,
                                        startMs = startMs,
                                        onFirstVideo = {
                                            readerState = ReaderState(currentChapter.id, 0, 0)
                                            updateChapterNavButtons()
                                        },
                                    )
                                    if (!started) {
                                        Log.w(
                                            "VideoPlayerActivity",
                                            "Cloudstream returned no playable links chapter=${currentChapter.id} " +
                                                "source=${manga.source.name}",
                                        )
                                        showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
                                    }
                                    // Cloudstream already performed its complete link resolution. Mark the
                                    // request as handled so the generic fallback does not invoke loadLinks twice.
                                    true
                                }
                                else -> null
                            } ?: run {
                                val pages = repo.getPages(currentChapter)
                                val fallbackVideos = pages.toFallbackVideos(repo)
                                if (fallbackVideos.isNotEmpty()) {
                                    availableVideos = fallbackVideos
                                    updateQualityButtonVisibility()
                                    currentVideoSource = manga.source
                                    currentVideoIndex = 0
                                    val selected = fallbackVideos[currentVideoIndex]
                                    val mergedHeaders = mergeHeaders(repo.getRequestHeaders(), headersToMap(selected.headers))
                                    pendingExternalSubtitles = selected.subtitleTracks
                                    pendingExternalAudio = selected.audioTracks
                                    Log.d(
                                        "VideoPlayerActivity",
                                        "Selected fallback video for chapter=${currentChapter.id} url=${selected.videoUrl} title=${selected.videoTitle} source=${manga.source.name} subtitles=${selected.subtitleTracks.size}",
                                    )
                                    startPlayback(
                                        selected.videoUrl,
                                        manga.source,
                                        mergedHeaders,
                                        startMs = startMs,
                                    )
                                    true
                                } else {
                                    val page = pages.firstOrNull()
                                    if (page != null) {
                                        val streamUrl = repo.getPageUrl(page)
                                        val streamHeaders = mergeHeaders(repo.getRequestHeaders(), page.headers)
                                        pendingExternalSubtitles = emptyList()
                                        pendingExternalAudio = emptyList()
                                        Log.d(
                                            "VideoPlayerActivity",
                                            "Selected fallback page for chapter=${currentChapter.id} url=$streamUrl headers=${streamHeaders.keys} source=${manga.source.name}",
                                        )
                                        availableVideos = emptyList()
                                        currentVideoIndex = 0
                                        updateQualityButtonVisibility()
                                        currentVideoSource = manga.source
                                        if (streamUrl.isTorrentLocator()) {
                                            startPlayback(
                                                url = streamUrl,
                                                source = manga.source,
                                                headers = streamHeaders,
                                                startMs = startMs,
                                            )
                                        } else {
                                            prepareAndPlay(streamUrl, manga.source, streamHeaders, startMs = startMs)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("VideoPlayer", "Failed to get stream URL", e)
                            if (resolvePlaybackException(e, normalizedUrl, source, headers, startMs)) {
                                return@launch
                            }
                            false
                        }

                        if (resolved) {
                            readerState = ReaderState(currentChapter.id, 0, 0)
                            updateChapterNavButtons()
                            android.util.Log.d("VideoPlayer", "Playing chapter: ${currentChapter.title}")
                        } else {
                            android.util.Log.e("VideoPlayer", "Failed to resolve stream URL for current chapter")
                            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
                        }
                    } else {
                        android.util.Log.e("VideoPlayer", "Current chapter not found")
                        showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("VideoPlayer", "Failed to load video", e)
                    if (resolvePlaybackException(e, normalizedUrl, source, headers, startMs)) {
                        return@launch
                    }
                    showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
                }
            }
        } else {
            android.util.Log.e("VideoPlayer", "Cannot resolve non-direct URL without manga info")
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
        }
    }

    private suspend fun resolvePlaybackException(
        error: Throwable,
        retryUrl: String,
        source: ParsersContentSource?,
        headers: Map<String, String>?,
        startMs: Long?,
    ): Boolean {
        if (!ExceptionResolver.canResolve(error)) {
            return false
        }
        val resolved = exceptionResolver.resolve(error, tryAutoResolve = false)
        if (resolved) {
            prepareAndPlay(retryUrl, source, headers, startMs)
        }
        return resolved
    }

    private fun extractTvBoxChapterPlaybackUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "tvbox" || uri.host != "chapter") {
            return null
        }
        return uri.getQueryParameter("play")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun resolvePlaybackPageAndPlay(
        url: String,
        source: ParsersContentSource?,
        headers: Map<String, String>?,
    ) {
        lifecycleScope.launch {
            val sniffed = runCatching {
                webViewExecutor.sniffMediaUrl(
                    url = url,
                    headers = headers,
                )
            }.onFailure {
                Log.w("VideoPlayer", "Failed to sniff playback page: $url", it)
            }.getOrNull()

            if (sniffed != null) {
                Log.d("VideoPlayer", "Sniffed playable media from web page: ${sniffed.url}")
                currentVideoSource = source
                availableVideos = emptyList()
                currentVideoIndex = 0
                updateQualityButtonVisibility()
                startPlayback(
                    url = sniffed.url,
                    source = source,
                    headers = mergeHeaders(headers, sniffed.headers),
                )
                return@launch
            }

            Log.d("VideoPlayer", "No playable media sniffed from web page, fallback to browser: $url")
            AppRouter(this@VideoPlayerActivity).openBrowser(
                url = url,
                source = source,
                title = currentMangaContent()?.title,
            )
            finish()
        }
    }

    private fun resolveLocalVideoUrl(
        manga: org.skepsun.kototoro.parsers.model.Content?,
        state: ReaderState?,
        url: String,
    ): String? {
        val chapters = manga?.chapters ?: return null
        val currentChapter = if (state != null) {
            chapters.find { it.id == state.chapterId }
        } else {
            chapters.find { it.url == url }
        } ?: return null
        val chapterUrl = currentChapter.url
        if (chapterUrl.startsWith("file://") || chapterUrl.startsWith("content://")) {
            return resolveIndexedLocalVideoUrl(chapterUrl, ReaderState(currentChapter.id, 0, 0)) ?: chapterUrl
        }
        val file = videoDownloadIndex.getFile(manga.id, currentChapter.id) ?: return null
        return file.toUri().toString()
    }

    private fun resolveIndexedLocalVideoUrl(url: String, state: ReaderState?): String? {
        val chapterId = state?.chapterId ?: return null
        val file = runCatching {
            val parsed = Uri.parse(url)
            val path = when {
                parsed.scheme.equals("file", ignoreCase = true) -> parsed.path
                parsed.scheme.isNullOrBlank() -> url
                else -> null
            } ?: return null
            val inputFile = File(path)
            val directory = inputFile.takeIf { it.isDirectory } ?: inputFile.parentFile?.takeIf { it.isDirectory }
            directory?.let { dir ->
                val fileName = ContentIndex.read(File(dir, "index.json"))?.getChapterFileName(chapterId)
                    ?: return@let null
                File(dir, fileName).takeIf { it.exists() && it.isFile }
            }
        }.getOrNull() ?: return null
        return file.toUri().toString()
    }

    private fun startPlayback(
        url: String,
        source: ParsersContentSource?,
        headers: Map<String, String>? = null,
        startMs: Long? = null,
        isTorrent: Boolean = url.isTorrentLocator(),
        forceHls: Boolean = false,
    ) {
        externalTrackLoadingJob?.cancel()
        playbackHealthCheckGeneration++
        if (isTorrent) {
            startTorrentPlayback(url, source, headers, startMs)
            return
        }
        if (currentMediaUrl != url) {
            torrentStreamService.release(currentMediaUrl)
        }
        hasRestoredProgress = false
        hasCurrentMediaLoaded = false
        currentMediaUrl = url
        currentVideoSource = source
        currentMediaHeaders = headers
        currentMediaForceHls = forceHls
        maybeLoadDanmaku()
        val declaredKind = when {
            isTorrent -> PlaybackMediaKind.TORRENT
            forceHls -> PlaybackMediaKind.HLS
            else -> PlaybackMediaKind.AUTO
        }
        val normalized = PlaybackRequestNormalizer.normalize(
            url = url,
            declaredKind = declaredKind,
            originalHeaders = headers.orEmpty(),
            isCloudstream = source is CloudstreamSource,
        )
        val mergedHeaders = normalized.headers
        videoLocalCacheProxy.resetSessionStats("startPlayback")
        val initialStartMs = startMs ?: resolveSavedPlaybackProgress(url)
        currentMediaStartMs = initialStartMs ?: 0L
        skipHistorySeekForCurrentMedia = initialStartMs != null
        effectivePlaybackConfig = playbackConfigOverride ?: VideoPlaybackPolicy.resolve(appSettings, devicePerformanceInfo)
        logEffectivePlaybackConfig()
        applyPlaybackOptions()
        applyAspectRatio()
        val defaultSpeed = appSettings.videoDefaultSpeed
        appSettings.videoPlaybackSpeed = defaultSpeed
        videoPlayer?.setRate(defaultSpeed.toDouble())

        Log.d(
            "VideoPlayerActivity",
            "Loading media. URL: $url, Headers: ${mergedHeaders.keys}",
        )
        val isHttpSource = url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        val useProxy = shouldUseLocalProxy(url, isHttpSource, source)
        val dynamicCloudstreamPlaylistUrl = createCloudstreamPlaylistProxyUrl(
            url = url,
            headers = mergedHeaders,
            source = source,
            forceHls = forceHls,
        )
        val (playUrl, playHeaders) = if (dynamicCloudstreamPlaylistUrl != null) {
            Log.d("VideoPlayerActivity", "Using rewritten Cloudstream playlist proxy for URL: $url")
            dynamicCloudstreamPlaylistUrl to emptyMap<String, String>()
        } else if (useProxy) {
            runCatching {
                val proxyUrl = videoLocalCacheProxy.getProxyUrl(url, mergedHeaders, source)
                proxyUrl to emptyMap<String, String>()
            }.getOrElse {
                Log.d("VideoPlayerActivity", "Proxy cache unavailable, fallback to origin URL", it)
                url to mergedHeaders
            }
        } else {
            Log.d("VideoPlayerActivity", "Bypass local proxy for URL: $url")
            url to mergedHeaders
        }
        Log.d("VideoPlayerActivity", "Resolved playback URL: $playUrl, useProxy=$useProxy")
        
        val requestId = "${++playbackRequestGeneration}:${playUrl.hashCode()}"
        val mediaKind = normalized.mediaKind.takeUnless { dynamicCloudstreamPlaylistUrl != null }
            ?: PlaybackMediaKind.HLS
        videoPlayer?.load(
            PlaybackRequest(
                requestId = requestId,
                uri = Uri.parse(playUrl),
                mediaKind = mediaKind,
                headers = playHeaders,
                subtitles = selectExternalSubtitlesForCurrentMedia(pendingExternalSubtitles),
                externalAudio = pendingExternalAudio,
                startPositionMs = initialStartMs ?: 0L,
            ),
        )
        pendingExternalSubtitles = emptyList()
        pendingExternalAudio = emptyList()
        updateTitleAndSubtitle()
        updatePlaybackMenu()
        if (!skipHistorySeekForCurrentMedia) {
            lifecycleScope.launch {
                restoreInitialSeekPercentFromHistory()
            }
        }
    }

    private fun startTorrentPlayback(
        locator: String,
        source: ParsersContentSource?,
        headers: Map<String, String>?,
        startMs: Long?,
    ) {
        val resolveAndPlay = {
            torrentResolutionJob?.cancel()
            torrentResolutionJob = lifecycleScope.launch {
                runCatching {
                    torrentStreamService.resolve(locator, headers.orEmpty())
                }.onSuccess { streamUrl ->
                    Log.i("VideoPlayerActivity", "Resolved torrent locator to local stream")
                    startPlayback(
                        url = streamUrl,
                        source = source,
                        headers = headers,
                        startMs = startMs,
                        isTorrent = false,
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.e("VideoPlayerActivity", "Failed to resolve torrent stream", error)
                    showPlayerMessage(R.string.torrent_stream_failed, SnackbarDuration.Long)
                }
            }
        }
        when (torrentConsent) {
            true -> resolveAndPlay()
            false -> showPlayerMessage(R.string.torrent_stream_rejected, SnackbarDuration.Long)
            null -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.torrent_stream_title)
                .setMessage(R.string.torrent_stream_warning)
                .setCancelable(false)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    torrentConsent = true
                    resolveAndPlay()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    torrentConsent = false
                    showPlayerMessage(R.string.torrent_stream_rejected, SnackbarDuration.Long)
                }
                .show()
        }
    }

    private fun shouldUseLocalProxy(
        url: String,
        isHttpSource: Boolean,
        source: ParsersContentSource?,
    ): Boolean {
        if (!isHttpSource) return false
        if (source is CloudstreamSource) {
            Log.d("VideoPlayerActivity", "Bypass local proxy for Cloudstream source: $url")
            return false
        }
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host == "127.0.0.1" || host == "localhost") {
            Log.d("VideoPlayerActivity", "Bypass local proxy for loopback URL: $url")
            return false
        }
        val lower = url.lowercase()
        val isMpd = lower.contains(".mpd")
        if (isMpd) return false
        val isM3u8 = lower.contains(".m3u8")
        if (isM3u8 && !ENABLE_M3U8_PROXY_CACHE) {
            Log.d(
                "VideoPlayerActivity",
                "Bypass local proxy for HLS; Media3 cache=${appSettings.videoCacheSizeMb}MB: $url",
            )
            return false
        }
        return true
    }

    private fun createCloudstreamPlaylistProxyUrl(
        url: String,
        headers: Map<String, String>,
        source: ParsersContentSource?,
        forceHls: Boolean,
    ): String? {
        if (source !is CloudstreamSource) return null
        val path = url.substringBefore('?').substringBefore('#')
        val hasStandardHlsSuffix = path.endsWith(".m3u8", ignoreCase = true)
        val requiresNonstandardHlsProxy = forceHls && !hasStandardHlsSuffix
        if (
            !requiresNonstandardHlsProxy &&
            !url.contains("/config-", ignoreCase = true)
        ) {
            return null
        }
        val identitySeed = buildString {
            append(url)
            headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
                append('|').append(key).append('=').append(value)
            }
        }
        return videoLocalCacheProxy.getDynamicProxyUrl(
            id = "cloudstream-config:${identitySeed.hashCode()}",
        ) { request ->
            val proxyBaseUrl = buildDynamicProxyBaseUrl(request)
            val targetUrl = request.queryParameters["target64"]
                ?.takeUnless(String::isBlank)
                ?.let(::decodeCloudstreamProxyTarget)
                ?: request.queryParameters["target"].takeUnless { it.isNullOrBlank() }
                ?: url
            val upstreamResponse = executeCloudstreamProxyRequest(targetUrl, headers)
            if (!upstreamResponse.isSuccessful) {
                upstreamResponse.close()
                return@getDynamicProxyUrl VideoLocalCacheProxy.DynamicResponse(
                    statusCode = upstreamResponse.code,
                    contentType = "text/plain; charset=utf-8",
                    body = "Cloudstream upstream failed: ${upstreamResponse.code}".toByteArray(Charsets.UTF_8),
                )
            }
            val body = upstreamResponse.body
            val contentType = upstreamResponse.header("Content-Type").orEmpty()
            if (body == null) {
                upstreamResponse.close()
                return@getDynamicProxyUrl VideoLocalCacheProxy.DynamicResponse(
                    statusCode = 500,
                    contentType = "text/plain; charset=utf-8",
                    body = "Cloudstream upstream body is null".toByteArray(Charsets.UTF_8),
                )
            }
            if (
                (requiresNonstandardHlsProxy && targetUrl == url) ||
                isCloudstreamPlaylistResponse(targetUrl, contentType)
            ) {
                val playlist = body.string()
                val playlistUrl = upstreamResponse.request.url.toString()
                upstreamResponse.close()
                val rewritten = rewriteCloudstreamPlaylistForProxy(
                    playlist = playlist,
                    baseUrl = playlistUrl,
                    proxyBaseUrl = proxyBaseUrl,
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "VideoPlayerActivity",
                        "Cloudstream playlist preview:\n${rewritten.lineSequence().take(8).joinToString("\n")}",
                    )
                }
                return@getDynamicProxyUrl VideoLocalCacheProxy.DynamicResponse(
                    statusCode = 200,
                    contentType = "application/vnd.apple.mpegurl; charset=utf-8",
                    headers = mapOf("Cache-Control" to "no-cache"),
                    body = rewritten.toByteArray(Charsets.UTF_8),
                )
            }
            if (forceHls && contentType.startsWith("image/", ignoreCase = true)) {
                val unwrapped = unwrapPngPrefixedStream(body.byteStream())
                if (unwrapped.wasUnwrapped || unwrapped.isTransportStream) {
                    Log.d(
                        "VideoPlayerActivity",
                        "Normalized image-labelled Cloudstream HLS segment target=$targetUrl " +
                            "contentType=$contentType unwrapped=${unwrapped.wasUnwrapped}",
                    )
                    return@getDynamicProxyUrl VideoLocalCacheProxy.DynamicResponse(
                        statusCode = upstreamResponse.code,
                        contentType = "video/mp2t",
                        headers = buildCloudstreamProxyHeaders(upstreamResponse)
                            .filterKeys { key ->
                                key.equals("Cache-Control", ignoreCase = true) ||
                                    (!unwrapped.wasUnwrapped && key.equals("Content-Length", ignoreCase = true))
                            },
                        bodyStream = unwrapped.stream,
                    )
                }
            }
            Log.d(
                "VideoPlayerActivity",
                "Cloudstream proxy passthrough target=$targetUrl contentType=$contentType",
            )
            VideoLocalCacheProxy.DynamicResponse(
                statusCode = upstreamResponse.code,
                contentType = contentType.ifBlank { "application/octet-stream" },
                headers = buildCloudstreamProxyHeaders(upstreamResponse),
                bodyStream = body.byteStream(),
            )
        }
    }

    private fun buildDynamicProxyBaseUrl(request: VideoLocalCacheProxy.DynamicRequest): String {
        val host = request.headers["host"].orEmpty().ifBlank { "127.0.0.1" }
        val dynamicIndex = request.pathSegments.indexOf("dynamic")
        val key = request.pathSegments.getOrNull(dynamicIndex + 1).orEmpty()
        return "http://$host/dynamic/$key"
    }

    private fun executeCloudstreamProxyRequest(
        url: String,
        headers: Map<String, String>,
    ): Response {
        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (key, value) -> header(key, value) }
            }
            .get()
            .build()
        return runCatching {
            contentHttpClient.newCall(request).execute()
        }.getOrElse { error ->
            throw IllegalStateException("Failed to proxy Cloudstream request: $url", error)
        }
    }

    private fun isCloudstreamPlaylistResponse(
        targetUrl: String,
        contentType: String,
    ): Boolean {
        val lowerUrl = targetUrl.lowercase()
        val lowerContentType = contentType.lowercase()
        return lowerUrl.contains(".m3u8") ||
            lowerUrl.contains("/config-") ||
            lowerUrl.contains("/data-") ||
            lowerContentType.contains("mpegurl") ||
            lowerContentType.contains("application/x-mpegurl")
    }

    private fun rewriteCloudstreamPlaylistForProxy(
        playlist: String,
        baseUrl: String,
        proxyBaseUrl: String,
    ): String {
        val currentToken = Uri.parse(baseUrl).getQueryParameter("t").orEmpty()
        return playlist.lineSequence()
            .map { line ->
                if (line.startsWith("#")) {
                    rewritePlaylistDirective(line, baseUrl, proxyBaseUrl, currentToken)
                } else {
                    rewritePlaylistDataLine(line, baseUrl, proxyBaseUrl, currentToken)
                }
            }
            .joinToString("\n")
    }

    private fun rewritePlaylistDirective(
        line: String,
        baseUrl: String,
        proxyBaseUrl: String,
        currentToken: String,
    ): String {
        return Regex("""URI="([^"]+)"""").replace(line) { match ->
            val rewritten = rewritePlaylistUrl(match.groupValues[1], baseUrl, proxyBaseUrl, currentToken)
            "URI=\"$rewritten\""
        }
    }

    private fun rewritePlaylistDataLine(
        line: String,
        baseUrl: String,
        proxyBaseUrl: String,
        currentToken: String,
    ): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return line
        return rewritePlaylistUrl(trimmed, baseUrl, proxyBaseUrl, currentToken)
    }

    private fun rewritePlaylistUrl(
        rawUrl: String,
        baseUrl: String,
        proxyBaseUrl: String,
        currentToken: String,
    ): String {
        val normalized = rawUrl.trim()
        if (normalized.isEmpty()) return rawUrl
        val absoluteUrl = runCatching {
            val parsed = URI(normalized)
            if (parsed.scheme.isNullOrBlank()) {
                URI(baseUrl).resolve(normalized).toString()
            } else {
                normalized
            }
        }.getOrDefault(normalized)
        val resolved = runCatching { URI(absoluteUrl) }.getOrNull() ?: return rawUrl
        if (resolved.scheme != "https" && resolved.scheme != "http") {
            return rawUrl
        }
        val normalizedTargetUrl = if (currentToken.isNotBlank()) {
            Uri.parse(absoluteUrl).buildUpon()
                .clearQuery()
                .appendQueryParameter("t", currentToken)
                .build()
                .toString()
        } else {
            absoluteUrl
        }
        val proxyUrlBuilder = Uri.parse(proxyBaseUrl).buildUpon()
        if (isImageLabelledHlsSegment(normalizedTargetUrl)) {
            // FFmpeg also considers the URL suffix when selecting the demuxer for an HLS segment.
            // Keep the upstream URL in the query while exposing the actual MPEG-TS container locally.
            proxyUrlBuilder.appendPath("segment.ts")
        }
        return proxyUrlBuilder
            .appendQueryParameter("target64", encodeCloudstreamProxyTarget(normalizedTargetUrl))
            .build()
            .toString()
    }

    private fun encodeCloudstreamProxyTarget(url: String): String {
        return Base64.encodeToString(
            url.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun decodeCloudstreamProxyTarget(value: String): String? {
        return runCatching {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun isImageLabelledHlsSegment(url: String): Boolean {
        return when (Uri.parse(url).lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "jpg", "jpeg", "png", "webp", "avif" -> true
            else -> false
        }
    }

    private fun buildCloudstreamProxyHeaders(response: Response): Map<String, String> {
        return buildMap {
            response.header("Content-Length")?.let { put("Content-Length", it) }
            response.header("Accept-Ranges")?.let { put("Accept-Ranges", it) }
            response.header("Content-Range")?.let { put("Content-Range", it) }
            response.header("Cache-Control")?.let { put("Cache-Control", it) }
        }
    }

    private fun headersToMap(headers: okhttp3.Headers?): Map<String, String> {
        if (headers == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return map
    }

    private suspend fun List<ContentPage>.toFallbackVideos(repo: ContentRepository): List<Video> {
        return mapNotNull { page ->
            val streamUrl = runCatching { repo.getPageUrl(page) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Video(
                videoUrl = streamUrl,
                videoTitle = page.playbackLabel.orEmpty(),
                resolution = page.playbackQuality,
                headers = page.headers
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { headers ->
                        Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                    },
                subtitleTracks = page.externalSubtitleTracks.map {
                    eu.kanade.tachiyomi.animesource.model.Track(it.url, it.lang)
                },
            )
        }
    }

    private suspend fun loadAndPlayCloudstreamChapter(
        repo: CloudstreamContentRepository,
        chapter: ContentChapter,
        source: ParsersContentSource,
        startMs: Long? = null,
        clearCache: Boolean = false,
        onFirstVideo: () -> Unit = {},
    ): Boolean {
        val loadingJob = currentCoroutineContext().job
        val playbackInstance = ++cloudstreamPlaybackInstance
        if (cloudstreamLinkJob !== loadingJob) {
            cloudstreamLinkJob?.cancel()
            cloudstreamLinkJob = loadingJob
        }
        cloudstreamFallbackJob?.cancel()
        cloudstreamFallbackJob = null
        selectionDialogState = null
        actionDialogState = null
        currentVideoSource = source
        availableVideos = emptyList()
        currentVideoIndex = 0
        pendingExternalSubtitles = emptyList()
        pendingExternalAudio = emptyList()
        updateQualityButtonVisibility()
        val videosByUrl = LinkedHashMap<String, Video>()
        val subtitlesByUrl = LinkedHashMap<String, eu.kanade.tachiyomi.animesource.model.Track>()
        var startedVideoUrl: String? = null

        fun refreshVideos(): List<Video> {
            val sortedVideos = videosByUrl.values
                .toList()
                .sortedCloudstreamVideos()
                .sortedBy { video -> video.internalData == TORRENT_VIDEO_MARKER }
            availableVideos = sortedVideos
            val activeVideoUrl = currentMediaUrl.takeIf { startedVideoUrl != null } ?: startedVideoUrl
            currentVideoIndex = activeVideoUrl
                ?.let { url -> sortedVideos.indexOfFirst { it.videoUrl == url } }
                ?.takeIf { it >= 0 }
                ?: 0
            updateQualityButtonVisibility()
            return sortedVideos
        }

        fun startPlayerFromAvailableLink(): Boolean {
            if (startedVideoUrl != null) return true
            val selected = refreshVideos().firstOrNull() ?: return false
            startedVideoUrl = selected.videoUrl
            currentVideoIndex = availableVideos.indexOfFirst { it.videoUrl == selected.videoUrl }
                .takeIf { it >= 0 }
                ?: 0
            pendingExternalSubtitles = subtitlesByUrl.values.toList()
            pendingExternalAudio = selected.audioTracks
            Log.d(
                "VideoPlayerActivity",
                "Starting Cloudstream playback after link collection url=${selected.videoUrl} " +
                    "available=${availableVideos.size}",
            )
            onFirstVideo()
            startPlayback(
                selected.videoUrl,
                source,
                headersToMap(selected.headers),
                startMs,
                isTorrent = selected.internalData == TORRENT_VIDEO_MARKER || selected.videoUrl.isTorrentLocator(),
                forceHls = selected.internalData == HLS_VIDEO_MARKER,
            )
            return true
        }

        // Some Cloudstream extensions host an AppCompat DialogFragment directly from loadLinks
        // (for example, a Turnstile challenge). Register this activity explicitly because the
        // plugin callback can outlive normal activity lifecycle callback ordering.
        com.lagradost.cloudstream3.CommonActivity.setActivityInstance(this)
        Log.d(
            "VideoPlayerActivity",
            "Cloudstream activity bridge registered chapter=${chapter.id} activity=${javaClass.simpleName}",
        )
        repo.getPlaybackEvents(chapter, clearCache).collect { event ->
            if (playbackInstance != cloudstreamPlaybackInstance) return@collect
            when (event) {
                is CloudstreamPlaybackEvent.Link -> {
                    val video = listOf(event.page).toFallbackVideos(repo).firstOrNull() ?: return@collect
                    val updatedVideo = video.copy(
                        videoTitle = video.videoTitle,
                        subtitleTracks = subtitlesByUrl.values.toList(),
                        internalData = when (event.type) {
                            com.lagradost.cloudstream3.utils.ExtractorLinkType.MAGNET,
                            com.lagradost.cloudstream3.utils.ExtractorLinkType.TORRENT -> TORRENT_VIDEO_MARKER
                            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 -> HLS_VIDEO_MARKER
                            else -> video.internalData
                        },
                    )
                    if (videosByUrl.putIfAbsent(video.videoUrl, updatedVideo) != null) return@collect
                    refreshVideos()
                    // Match Cloudstream's generator behavior: publish and consume links as callbacks
                    // arrive instead of waiting for every extractor to finish.
                    startPlayerFromAvailableLink()
                }
                is CloudstreamPlaybackEvent.Subtitle -> {
                    if (!event.track.url.startsWith("http://") && !event.track.url.startsWith("https://")) {
                        Log.d(
                            "VideoPlayerActivity",
                            "Ignoring Cloudstream subtitle with unsupported URL",
                        )
                        return@collect
                    }
                    val track = eu.kanade.tachiyomi.animesource.model.Track(
                        url = resolveExternalSubtitleUrl(event.track.url, event.track.headers),
                        lang = event.track.lang,
                    )
                    if (subtitlesByUrl.putIfAbsent(track.url, track) != null) return@collect
                    val updatedVideos = videosByUrl.values.map { video ->
                        video.copy(
                            videoTitle = video.videoTitle,
                            subtitleTracks = subtitlesByUrl.values.toList(),
                        )
                    }
                    videosByUrl.clear()
                    updatedVideos.forEach { videosByUrl[it.videoUrl] = it }
                    pendingExternalSubtitles = subtitlesByUrl.values.toList()
                    refreshVideos()
                }
            }
        }
        if (playbackInstance != cloudstreamPlaybackInstance) return false
        refreshVideos()
        return startPlayerFromAvailableLink()
    }

    private fun mergeHeaders(
        base: Map<String, String>?,
        extra: Map<String, String>?,
    ): Map<String, String> {
        if (base.isNullOrEmpty()) return extra.orEmpty()
        if (extra.isNullOrEmpty()) return base
        return base.toMutableMap().apply { putAll(extra) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPlayerUiState(playerUiState)
        applyControlsAlpha()
        applySubtitleOverlayStyle()
        updateTitleAndSubtitle()
    }

    private fun toggleUiVisibility() {
        if (isScreenLocked) return // no-op when locked
        setUiIsVisible(!isUiVisible)
    }

    private fun applyControlsAlpha() {
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, playerRoot).setAppearanceLightStatusBars(false)
    }

    private fun setUiIsVisible(visible: Boolean) {
        applyPlayerUiState(if (visible) PlayerUiState.ControlsVisible else PlayerUiState.Hidden)
    }

    private fun pauseControlsAutoHide() {
        playerRoot.removeCallbacks(hideUiRunnable)
    }

    private fun restartControlsAutoHide() {
        playerRoot.removeCallbacks(hideUiRunnable)
        if (playerUiState == PlayerUiState.ControlsVisible) {
            playerRoot.postDelayed(hideUiRunnable, autoHideDelayMs.toLong())
        }
    }

    private fun applyPlayerUiState(state: PlayerUiState) {
        playerUiState = state
        isUiVisible = state == PlayerUiState.ControlsVisible

        val controlsVisible = state == PlayerUiState.ControlsVisible
        systemUiController.setSystemUiVisible(false)

        if (state != PlayerUiState.Locked) unlockButtonVisible = false

        playerRoot.removeCallbacks(hideUiRunnable)
        playerRoot.removeCallbacks(progressUpdateRunnable)
        playerRoot.removeCallbacks(hideLockUiRunnable)
        playerRoot.removeCallbacks(controllerProgressRunnable)

        if (controlsVisible) {
            if (!isHorizontalScrubbing && !isUserScrubbing && verticalAdjustMode == 0) {
                playerRoot.postDelayed(hideUiRunnable, autoHideDelayMs.toLong())
            }
            playerRoot.postDelayed(progressUpdateRunnable, progressUpdateIntervalMs.toLong())
            playerRoot.postDelayed(controllerProgressRunnable, progressUpdateIntervalMs.toLong())
        }
        syncComposeControlState()
        spaceSwitcherDelegate.setControlsVisible(playerUiState == PlayerUiState.ControlsVisible)
    }

    // ==================== Screen Lock ====================

    private val lockAutoHideDelayMs = 3000L
    private val hideLockUiRunnable = Runnable { unlockButtonVisible = false }

    private fun enterScreenLock() {
        isScreenLocked = true
        spaceSwitcherDelegate.invalidateAvailability()
        updateScreenLockButtonState()
        applyPlayerUiState(PlayerUiState.Locked)
    }

    private fun exitScreenLock() {
        isScreenLocked = false
        spaceSwitcherDelegate.invalidateAvailability()
        updateScreenLockButtonState()
        playerRoot.removeCallbacks(hideLockUiRunnable)
        unlockButtonVisible = false
        applyPlayerUiState(PlayerUiState.ControlsVisible)
    }

    private fun showLockedUi() {
        playerRoot.removeCallbacks(hideLockUiRunnable)
        unlockButtonVisible = true
        playerRoot.postDelayed(hideLockUiRunnable, lockAutoHideDelayMs)
    }

    // ==================== Intro/Outro Skip ====================

    private fun loadIntroOutroSettings() {
        val manga = currentMangaContent()
        currentMangaId = manga?.id ?: 0L
        if (currentMangaId != 0L) {
            introEndMs = appSettings.getIntroEndMs(currentMangaId)
            outroStartMs = appSettings.getOutroStartMs(currentMangaId)
        }
        hasSkippedIntro = false
        hasTriggeredOutro = false
        updateIntroOutroButtonState()
    }

    private fun trySkipIntro() {
        if (introEndMs > 0 && !hasSkippedIntro) {
            val pos = videoPlayer?.positionMs ?: return
            if (pos < introEndMs) {
                hasSkippedIntro = true
                videoPlayer?.seekTo(introEndMs)
                showPlayerMessage(R.string.video_skipping_intro)
            }
        }
    }

    private fun updateIntroOutroButtonState() {
        syncComposeControlState()
    }

    private fun updateScreenLockButtonState() {
        syncComposeControlState()
    }

    private fun showOverflowMenu(anchorBounds: IntRect = lastMoreAnchorBounds) {
        if (anchorBounds != IntRect.Zero) {
            lastMoreAnchorBounds = anchorBounds
        }
        actionDialogState = VideoActionDialogState(
            title = getString(R.string.options),
            items = listOf(
                VideoActionDialogItem(
                    title = getString(R.string.video_reload),
                    iconRes = R.drawable.ic_retry,
                    onClick = ::reloadPlayback,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.open_external_player),
                    iconRes = R.drawable.ic_open_external,
                    onClick = ::openInExternalPlayer,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.cast_to_device),
                    iconRes = R.drawable.ic_cast,
                    onClick = ::showDlnaDeviceSheet,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.video_picture_in_picture),
                    iconRes = R.drawable.ic_picture_in_picture,
                    onClick = ::enterPictureInPicture,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.ai_video_enhancement_settings),
                    iconRes = R.drawable.ic_auto_fix,
                    onClick = ::showVideoSuperResolutionSheet,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.video_screenshot),
                    iconRes = R.drawable.ic_save,
                    onClick = ::takeScreenshot,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.video_aspect_ratio),
                    iconRes = R.drawable.ic_aspect_ratio,
                    onClick = ::showAspectRatioDialog,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.save_manga_video),
                    iconRes = R.drawable.ic_download,
                    onClick = ::downloadCurrentChapter,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.video_detail),
                    iconRes = R.drawable.ic_info_outline,
                    onClick = ::openVideoDetails,
                ),
                VideoActionDialogItem(
                    title = getString(R.string.settings),
                    iconRes = R.drawable.ic_settings,
                    onClick = {
                        showVideoSettingsPanel(
                            anchorBounds = lastMoreAnchorBounds,
                            onBack = { showOverflowMenu(lastMoreAnchorBounds) },
                        )
                    },
                ),
            ),
            anchorBounds = lastMoreAnchorBounds,
            columns = 2,
        )
    }

    private fun buildPlayerSettingsActions(): List<PlayerSettingsAction> {
        val enabledText = getString(R.string.enabled)
        val disabledText = getString(R.string.disabled)
        return listOf(
            PlayerSettingsAction(
                title = getString(R.string.video_default_speed),
                subtitle = "%.2fx".format(appSettings.videoDefaultSpeed),
                iconRes = org.skepsun.kototoro.R.drawable.ic_timelapse,
                onClick = ::showDefaultPlaybackSpeedDialog,
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_seek_forward_time),
                subtitle = "${appSettings.videoSeekForwardMs / 1000}s",
                iconRes = org.skepsun.kototoro.R.drawable.ic_fast_forward,
                onClick = {
                    showSeekIntervalDialog(
                        titleRes = R.string.video_seek_forward_time,
                        currentMs = appSettings.videoSeekForwardMs,
                    ) { appSettings.videoSeekForwardMs = it }
                },
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_seek_backward_time),
                subtitle = "${appSettings.videoSeekBackwardMs / 1000}s",
                iconRes = org.skepsun.kototoro.R.drawable.ic_fast_rewind,
                onClick = {
                    showSeekIntervalDialog(
                        titleRes = R.string.video_seek_backward_time,
                        currentMs = appSettings.videoSeekBackwardMs,
                    ) { appSettings.videoSeekBackwardMs = it }
                },
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_danmaku_enabled),
                subtitle = if (appSettings.videoDanmakuEnabled) enabledText else disabledText,
                iconRes = org.skepsun.kototoro.R.drawable.ic_danmaku,
                isChecked = appSettings.videoDanmakuEnabled,
                onClick = {
                    appSettings.videoDanmakuEnabled = !appSettings.videoDanmakuEnabled
                    applyDanmakuSettings()
                },
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_double_tap_seek),
                subtitle = if (appSettings.videoDoubleTapSeekEnabled) enabledText else disabledText,
                iconRes = org.skepsun.kototoro.R.drawable.ic_gesture_double_tap,
                isChecked = appSettings.videoDoubleTapSeekEnabled,
                onClick = {
                    appSettings.videoDoubleTapSeekEnabled = !appSettings.videoDoubleTapSeekEnabled
                },
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_volume_boost),
                subtitle = if (appSettings.videoVolumeBoostEnabled) enabledText else disabledText,
                iconRes = org.skepsun.kototoro.R.drawable.ic_settings,
                isChecked = appSettings.videoVolumeBoostEnabled,
                onClick = {
                    appSettings.videoVolumeBoostEnabled = !appSettings.videoVolumeBoostEnabled
                    applyPlaybackOptions()
                },
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_auto_next),
                subtitle = if (appSettings.videoAutoNextEnabled) enabledText else disabledText,
                iconRes = org.skepsun.kototoro.R.drawable.ic_action_resume,
                isChecked = appSettings.videoAutoNextEnabled,
                onClick = {
                    appSettings.videoAutoNextEnabled = !appSettings.videoAutoNextEnabled
                },
            ),
        )
    }

    private fun showChapterSelectionPanel(anchorBounds: IntRect) {
        val chapters = playerChapterList()
        if (chapters.isEmpty()) return

        val currentId = readerState?.chapterId
        val groups = groupPlayerChapters(chapters)
        actionDialogState = null
        chapterDialogState = VideoChapterDialogState(
            title = getString(R.string.chapters),
            groups = groups,
            currentChapterId = currentId,
            initialPage = findPlayerChapterGroupIndex(groups, currentId),
            initialGridView = chaptersViewModel.isChaptersInGridView.value,
            ungroupedTitle = getString(R.string.video_chapter_group_ungrouped),
            anchorBounds = anchorBounds,
        )
    }

    private fun playerChapterList(): List<ContentChapter> {
        return chaptersViewModel.getAllChapters().ifEmpty {
            currentMangaContent()?.chapters.orEmpty()
        }
    }

    private fun downloadCurrentChapter() {
        val manga = mangaContent ?: run {
            showPlayerMessage(R.string.operation_not_supported)
            return
        }
        val chapterId = readerState?.chapterId ?: run {
            showPlayerMessage(R.string.operation_not_supported)
            return
        }
        val task = DownloadTask(
            mangaId = manga.id,
            displayMangaId = manga.id,
            isPaused = false,
            isSilent = false,
            chaptersIds = longArrayOf(chapterId),
            destination = null,
            format = null,
            allowMeteredNetwork = true,
        )
        lifecycleScope.launch {
            downloadScheduler.schedule(setOf(manga to task))
            showPlayerMessage(R.string.download_started)
        }
    }

    private fun toggleIntroMarker() {
        if (currentMangaId == 0L) return
        if (introEndMs > 0) {
            introEndMs = 0L
            appSettings.clearIntroEndMs(currentMangaId)
            showPlayerMessage(R.string.video_skip_intro_cleared)
        } else {
            val pos = videoPlayer?.positionMs ?: return
            introEndMs = pos
            appSettings.setIntroEndMs(currentMangaId, pos)
            showPlayerMessage(getString(R.string.video_skip_intro_set, formatTimeMs(pos)))
        }
        updateIntroOutroButtonState()
    }

    private fun toggleOutroMarker() {
        if (currentMangaId == 0L) return
        if (outroStartMs > 0) {
            outroStartMs = 0L
            appSettings.clearOutroStartMs(currentMangaId)
            showPlayerMessage(R.string.video_skip_outro_cleared)
        } else {
            val pos = videoPlayer?.positionMs ?: return
            outroStartMs = pos
            appSettings.setOutroStartMs(currentMangaId, pos)
            showPlayerMessage(getString(R.string.video_skip_outro_set, formatTimeMs(pos)))
        }
        updateIntroOutroButtonState()
    }

    private fun updatePlaybackMenu() {
        syncComposeControlState()
    }

    // 简单时间格式化（mm:ss ?hh:mm:ss?
    // forceHours: 当总时长包含小时时，强制显示小时位保持格式一?
    private fun formatTimeMs(ms: Long, forceHours: Boolean = false): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hours = (totalSec / 3600)
        val minutes = ((totalSec % 3600) / 60)
        val seconds = (totalSec % 60)
        return if (hours > 0 || forceHours) String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    private fun applyPlaybackBackground() {
        playerRoot.setBackgroundColor(android.graphics.Color.BLACK)
    }

    private fun deriveEpisodeTitle(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            val raw = uri.lastPathSegment ?: url
            URLDecoder.decode(raw, "UTF-8")
        }.getOrElse { url }
    }

    private fun currentReaderStateOrIntent(): ReaderState? {
        return readerState
    }

    private fun extractChapterInfo(): Pair<String, String> {
        // Extract manga and state from intent
        val manga = currentMangaContent()
        val state = currentReaderStateOrIntent()
        val fallbackUrl = currentMediaUrl ?: intent.getStringExtra(AppRouter.KEY_URL)
        
        // Extract title: prioritize manga.title, then KEY_TITLE, then URL-derived
        val title = manga?.title
            ?: intent.getStringExtra(AppRouter.KEY_TITLE).takeUnless { it.isNullOrBlank() }
            ?: fallbackUrl?.let { deriveEpisodeTitle(it) }
            ?: ""
        
        // Extract chapter name: prioritize chapter.name from manga.chapters, then URL-derived
        val chapterName = if (manga != null && state != null) {
            val chapter = manga.chapters?.find { it.id == state.chapterId }
            if (manga.source is CloudstreamSource && chapter != null) {
                resolveCloudstreamEpisodeTitle(chapter.title, chapter.number.toInt())
            } else {
                chapter?.title?.takeIf { it.isNotBlank() }
                    ?: fallbackUrl?.let { deriveEpisodeTitle(it) }
                    ?: ""
            }
        } else {
            fallbackUrl?.let { deriveEpisodeTitle(it) }
                ?: ""
        }
        
        return Pair(title, chapterName)
    }

    private fun updateTitleAndSubtitle() {
        syncComposeControlState()
    }

    private fun loadPendingExternalTracks() {
        autoSelectTracksByLanguage()
    }

    private fun selectExternalSubtitlesForCurrentMedia(
        tracks: List<eu.kanade.tachiyomi.animesource.model.Track>,
    ): List<eu.kanade.tachiyomi.animesource.model.Track> {
        if (currentVideoSource !is CloudstreamSource || tracks.size <= 1) return tracks
        val selected = when (val selection = userManualSubtitleSelection) {
            ManualSubtitleSelection.Off -> null
            is ManualSubtitleSelection.Track -> tracks.firstOrNull { track ->
                track.lang.equals(selection.language, ignoreCase = true) ||
                    track.lang.equals(selection.title, ignoreCase = true)
            }
            null -> {
                val locale = java.util.Locale.getDefault()
                val languageNames = listOf(
                    locale.language,
                    locale.getDisplayLanguage(java.util.Locale.ENGLISH),
                    locale.displayLanguage,
                ).filter { it.isNotBlank() }
                tracks.firstOrNull { track ->
                    languageNames.any { language ->
                        track.lang.contains(language, ignoreCase = true)
                    }
                } ?: tracks.firstOrNull()
            }
        }
        Log.d(
            "VideoPlayerActivity",
            "Cloudstream subtitle auto-selection available=${tracks.size} selected=${selected?.lang}",
        )
        return listOfNotNull(selected)
    }

    private fun resolveExternalSubtitleUrl(url: String, headers: Map<String, String>? = null): String {
        val resolvedHeaders = headers.orEmpty()
        if (resolvedHeaders.isEmpty()) return url
        return runCatching {
            videoLocalCacheProxy.getProxyUrl(url, resolvedHeaders, currentVideoSource)
        }.onFailure { error ->
            Log.w("VideoPlayerActivity", "Failed to proxy external subtitle: $url", error)
        }.getOrDefault(url)
    }

    private fun pollSubtitleText() {
        // Media3 pushes CueGroup updates through VideoPlayerEngine.Listener.
    }

    /**
     * Update the subtitle overlay TextView with the given text.
     * Can be called from any thread ?dispatches to UI thread.
     */
    fun applySubtitleOverlayStyle() {
        val settings = appSettings
        subtitleOverlayState = subtitleOverlayState.copy(
            fontSizeSp = settings.videoSubtitleFontSize,
            bold = settings.videoSubtitleBold,
            italic = settings.videoSubtitleItalic,
            textColor = settings.videoSubtitleTextColor,
            borderColor = settings.videoSubtitleBorderColor,
            borderSize = settings.videoSubtitleBorderSize,
            backgroundColor = settings.videoSubtitleBgColor,
            alignX = settings.videoSubtitleAlignX,
            bottomPositionDp = settings.videoSubtitlePosition,
        )
    }

    private fun updateSubtitleOverlay(text: String?) {
        runOnUiThread {
            applySubtitleOverlayStyle()
            subtitleOverlayState = subtitleOverlayState.copy(text = text?.takeIf(String::isNotBlank))
        }
    }

    /**
     * Auto-select subtitle and audio tracks matching the system language.
     * Called after file is loaded and tracks are available.
     */
    private fun autoSelectTracksByLanguage() {
        val player = videoPlayer ?: return
        val manualSelection = userManualSubtitleSelection
        Log.d("VideoPlayerActivity", "autoSelectTracksByLanguage: manualSelection=$manualSelection")

        // Auto-select subtitle track: prefer user's manual selection, fall back to system language
        val subTracks = player.getSubtitleTracks()
        if (subTracks.isNotEmpty()) {
            when (manualSelection) {
                is ManualSubtitleSelection.Off -> {
                    // User explicitly turned off subtitles
                    player.setSubtitleTrack(null)
                    Log.d("VideoPlayerActivity", "Restored manual selection: subtitles off")
                }
                is ManualSubtitleSelection.Track -> {
                    // Try to find a matching track by language or title
                    val match = subTracks.find { track ->
                        (!manualSelection.language.isNullOrBlank() && track.language?.equals(manualSelection.language, ignoreCase = true) == true) ||
                        (!manualSelection.title.isNullOrBlank() && track.title?.equals(manualSelection.title, ignoreCase = true) == true)
                    }
                    if (match != null && !match.isSelected) {
                        player.setSubtitleTrack(match.id)
                        Log.d("VideoPlayerActivity", "Restored manual subtitle: ${match.displayName()}")
                    } else if (match == null) {
                        // Manual selection not available in new file, fall back to system language
                        autoSelectSubtitleBySystemLanguage(subTracks)
                    }
                }
                null -> {
                    // No manual selection yet, use system language
                    autoSelectSubtitleBySystemLanguage(subTracks)
                }
            }
        }

        // Auto-select audio track matching system language (if multiple audio tracks exist)
        val audioTracks = player.getAudioTracks()
        if (audioTracks.size > 1) {
            val systemLang = java.util.Locale.getDefault().language
            val match = audioTracks.find { it.language?.startsWith(systemLang, ignoreCase = true) == true }
            if (match != null && !match.isSelected) {
                player.setAudioTrack(match.id)
                Log.d("VideoPlayerActivity", "Auto-selected audio: ${match.displayName()}")
            }
        }
    }

    private fun autoSelectSubtitleBySystemLanguage(subTracks: List<VideoPlayerEngine.TrackInfo>) {
        val systemLang = java.util.Locale.getDefault().language
        val player = videoPlayer ?: return
        val match = subTracks.find { it.language?.startsWith(systemLang, ignoreCase = true) == true }
        if (match != null && !match.isSelected) {
            player.setSubtitleTrack(match.id)
            Log.d("VideoPlayerActivity", "Auto-selected subtitle by system lang: ${match.displayName()}")
        }
    }

    private sealed class ManualSubtitleSelection {
        data object Off : ManualSubtitleSelection()
        data class Track(val language: String?, val title: String?) : ManualSubtitleSelection()
    }

    fun applySuperResolutionFromSettings() {
        effectivePlaybackConfig = playbackConfigOverride ?: VideoPlaybackPolicy.resolve(appSettings, devicePerformanceInfo)
        val remembered = appSettings.videoEnhancementRememberAcrossVideos &&
            appSettings.videoEnhancementRememberedEnabled
        val shouldEnable = enhancementSessionEnabled || remembered
        if (!shouldEnable || !effectivePlaybackConfig.allowShaderPipeline || isInPictureInPictureMode) {
            Log.i(
                "VideoPlayerActivity",
                "Enhancement route bypassed shouldEnable=$shouldEnable " +
                    "allowShader=${effectivePlaybackConfig.allowShaderPipeline} pip=$isInPictureInPictureMode",
            )
            fallbackToDirectOutput()
            return
        }
        if (!::enhancementView.isInitialized || !enhancementSurfaceReady) {
			if (::enhancementView.isInitialized) {
				enhancementView.visibility = View.VISIBLE
				enhancementView.alpha = 0f
			}
            Log.w(
                "VideoPlayerActivity",
                "Enhancement requested but surface unavailable viewReady=${::enhancementView.isInitialized} " +
                    "surfaceReady=$enhancementSurfaceReady",
            )
            return
        }
        val wasEnabled = enhancementSessionEnabled
        enhancementSessionEnabled = true
        val config = VideoEnhancementConfig(
            algorithm = appSettings.videoEnhancementAlgorithm,
            anime4KPreset = appSettings.videoAnime4KPreset,
            fsrSharpness = appSettings.videoFsrSharpness,
            sourceWidth = videoPlayer?.getPropertyString("video-params/w")?.toIntOrNull() ?: 1,
            sourceHeight = videoPlayer?.getPropertyString("video-params/h")?.toIntOrNull() ?: 1,
        )
        val previousConfig = currentEnhancementConfig
        val pipelineChanged = !wasEnabled || previousConfig == null ||
            previousConfig.algorithm != config.algorithm ||
            previousConfig.anime4KPreset != config.anime4KPreset ||
            previousConfig.sourceWidth != config.sourceWidth ||
            previousConfig.sourceHeight != config.sourceHeight
        currentEnhancementConfig = config
        enhancementView.configure(config)
        enhancementView.visibility = View.VISIBLE
		enhancementView.alpha = 1f
        if (!enhancementOutputAttached) {
            playerView.player = null
            playerView.visibility = View.GONE
            videoPlayer?.setVideoSurface(enhancementVideoSurface)
            enhancementOutputAttached = true
            Log.i(
                "VideoPlayerActivity",
                "Enhancement output attached algorithm=${appSettings.videoEnhancementAlgorithm} " +
                    "preset=${appSettings.videoAnime4KPreset} sharpness=${appSettings.videoFsrSharpness}",
            )
        } else {
            Log.i(
                "VideoPlayerActivity",
                "Enhancement config updated algorithm=${appSettings.videoEnhancementAlgorithm} " +
                    "preset=${appSettings.videoAnime4KPreset} sharpness=${appSettings.videoFsrSharpness}",
            )
        }
        if (pipelineChanged) scheduleEnhancementFirstFrameFallback()
    }

    private fun scheduleEnhancementFirstFrameFallback() {
        enhancementDisplayedFirstFrame = false
        val generation = ++enhancementFallbackGeneration
        var playbackIntentStartedAtMs: Long? = null
        fun checkFirstFrame() {
            if (generation != enhancementFallbackGeneration || !enhancementSessionEnabled ||
                enhancementDisplayedFirstFrame
            ) return
            val player = videoPlayer?.player ?: return
            val hasPlaybackIntent = player.playWhenReady && player.mediaItemCount > 0
            if (!hasPlaybackIntent) {
                playbackIntentStartedAtMs = null
            } else {
                val now = android.os.SystemClock.elapsedRealtime()
                val startedAt = playbackIntentStartedAtMs ?: now.also { playbackIntentStartedAtMs = it }
                if (now - startedAt >= 3_000L) {
                    Log.w("VideoPlayerActivity", "No enhanced GL first frame after 3s of playback intent; falling back")
                    fallbackToDirectOutput()
                    return
                }
            }
            playerRoot.postDelayed(::checkFirstFrame, 120L)
        }
        playerRoot.post(::checkFirstFrame)
    }

    private fun fallbackToDirectOutput() {
        enhancementSessionEnabled = false
        enhancementOutputAttached = false
        enhancementDisplayedFirstFrame = false
        enhancementFallbackGeneration++
        if (::enhancementView.isInitialized) {
			enhancementView.visibility = View.VISIBLE
			enhancementView.alpha = 0f
		}
        if (::playerView.isInitialized) {
            playerView.visibility = View.VISIBLE
            videoPlayer?.attachPlayerView(playerView)
        }
    }

    private fun logEffectivePlaybackConfig() {
        Log.i(
            "VideoPlayerActivity",
            "Playback policy: tier=${devicePerformanceInfo.tier} score=${devicePerformanceInfo.score} " +
                "ramMb=${devicePerformanceInfo.totalRamMb} cpu=${devicePerformanceInfo.cpuCores} " +
                "superRes=${effectivePlaybackConfig.superResolutionMode} shaders=${effectivePlaybackConfig.allowShaderPipeline}"
        )
    }

    private fun handlePlaybackFallback(trigger: String, detail: String?): Boolean {
        if (currentVideoSource !is CloudstreamSource) return false
        if (cloudstreamFallbackJob?.isActive == true) return true
        val activeVideoIndex = availableVideos
            .indexOfFirst { it.videoUrl == currentMediaUrl }
            .takeIf { it >= 0 }
            ?: currentVideoIndex
        currentVideoIndex = activeVideoIndex
        val firstCandidate = availableVideos.getOrNull(activeVideoIndex + 1) ?: run {
            Log.d(
                "VideoPlayer",
                "Cloudstream playback failed without another mirror trigger=$trigger " +
                    "index=$activeVideoIndex available=${availableVideos.size} detail=$detail",
            )
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
            return false
        }
        cloudstreamFallbackJob = lifecycleScope.launch {
            val candidateIndex = availableVideos.indexOfFirst { it.videoUrl == firstCandidate.videoUrl }
            if (candidateIndex < 0) return@launch
            Log.d(
                "VideoPlayer",
                "Cloudstream playback failed, trying next mirror trigger=$trigger " +
                    "from=$activeVideoIndex to=$candidateIndex " +
                    "available=${availableVideos.size} detail=$detail",
            )
            switchVideoQuality(firstCandidate, currentMediaStartMs)
        }.also { job ->
            job.invokeOnCompletion {
                if (cloudstreamFallbackJob === job) cloudstreamFallbackJob = null
            }
        }
        return true
    }

    private fun scheduleCloudstreamPlaybackHealthCheck() {
        if (currentVideoSource !is CloudstreamSource) return
        val generation = playbackHealthCheckGeneration
        val initialPositionMs = videoPlayer?.positionMs ?: return
        playerRoot.postDelayed({
            if (generation != playbackHealthCheckGeneration || currentVideoSource !is CloudstreamSource) {
                return@postDelayed
            }
            val player = videoPlayer ?: return@postDelayed
            if (
                isStalledCloudstreamPlayback(
                    durationMs = player.durationMs,
                    initialPositionMs = initialPositionMs,
                    currentPositionMs = player.positionMs,
                )
            ) {
                handlePlaybackFallback(
                    trigger = "stalled_after_file_loaded",
                    detail = "durationMs=${player.durationMs} positionMs=${player.positionMs}",
                )
            }
        }, CLOUDSTREAM_PLAYBACK_HEALTH_CHECK_DELAY_MS)
    }

    private fun showFallbackHintOnce(reason: PlaybackFallbackReason) {
        if (!shownFallbackHints.add(reason)) return
        val messageRes = when (reason) {
            PlaybackFallbackReason.SUPER_RES_DISABLED -> R.string.video_fallback_super_res_disabled
            PlaybackFallbackReason.RENDERER_DOWNGRADED -> R.string.video_fallback_renderer_downgraded
            PlaybackFallbackReason.CONSERVATIVE_MODE -> R.string.video_fallback_conservative_mode
        }
        showPlayerMessage(
            messageRes = messageRes,
            duration = SnackbarDuration.Long,
            actionLabel = getString(R.string.settings),
            onAction = { showVideoSettingsPanel() },
        )
    }

    private fun showPlaybackErrorHintOnce(category: PlaybackFailureCategory) {
        if (!shownPlaybackErrorHints.add(category)) return
        val messageRes = when (category) {
            PlaybackFailureCategory.NETWORK_OR_SOURCE -> R.string.network_error
            PlaybackFailureCategory.COMPATIBILITY -> R.string.error_occurred
            PlaybackFailureCategory.UNKNOWN -> R.string.error_occurred
        }
        showPlayerMessage(
            messageRes = messageRes,
            duration = SnackbarDuration.Long,
            actionLabel = getString(R.string.settings),
            onAction = { showVideoSettingsPanel() },
        )
    }

    private fun showVideoSettingsPanel(
        anchorBounds: IntRect = lastSettingsAnchorBounds,
        onBack: (() -> Unit)? = null,
    ) {
        if (anchorBounds != IntRect.Zero) {
            lastSettingsAnchorBounds = anchorBounds
        }
        actionDialogState = VideoActionDialogState(
            title = getString(R.string.settings),
            items = buildPlayerSettingsActions().map { action ->
                VideoActionDialogItem(
                    title = action.title,
                    subtitle = action.subtitle,
                    iconRes = action.iconRes,
                    checked = action.isChecked,
                    onClick = action.onClick,
                )
            },
            anchorBounds = lastSettingsAnchorBounds,
            onBack = onBack,
        )
    }

    private fun buildSubtitleSettingsDialogState(): VideoSubtitleSettingsDialogState {
        val player = videoPlayer
        val tracks = player?.getSubtitleTracks().orEmpty()
        val trackOptions = arrayOf(getString(org.skepsun.kototoro.R.string.video_subtitle_off)) +
            tracks.map { it.displayName() }.toTypedArray()
        return VideoSubtitleSettingsDialogState(
            fontSizeSp = appSettings.videoSubtitleFontSize,
            bold = appSettings.videoSubtitleBold,
            italic = appSettings.videoSubtitleItalic,
            textColor = appSettings.videoSubtitleTextColor,
            borderColor = appSettings.videoSubtitleBorderColor,
            borderSize = appSettings.videoSubtitleBorderSize,
            backgroundColor = appSettings.videoSubtitleBgColor,
            alignX = appSettings.videoSubtitleAlignX,
            position = appSettings.videoSubtitlePosition,
            subtitleTrackOptions = if (tracks.isEmpty()) emptyList() else trackOptions.toList(),
            subtitleTrackSelectedIndex = subtitlePanelSelectedIndex,
            anchorBounds = lastSettingsAnchorBounds,
        )
    }

    private fun showSubtitleSettingsDialog() {
        subtitlePanelSelectedIndex = videoPlayer?.getSubtitleTracks()
            ?.indexOfFirst { it.isSelected }
            ?.takeIf { it >= 0 }
            ?.let { it + 1 }
            ?: 0
        subtitleSettingsDialogVisible = true
    }

    private fun selectSubtitleTrack(which: Int) {
        subtitlePanelSelectedIndex = which
        val player = videoPlayer ?: return
        val tracks = player.getSubtitleTracks()
        if (which == 0) {
            player.setSubtitleTrack(null)
            userManualSubtitleSelection = ManualSubtitleSelection.Off
        } else {
            val track = tracks.getOrNull(which - 1) ?: return
            player.setSubtitleTrack(track.id)
            userManualSubtitleSelection = ManualSubtitleSelection.Track(
                language = track.language,
                title = track.title,
            )
        }
    }

    private fun showAudioTrackDialog() {
        val player = videoPlayer ?: return
        val tracks = player.getAudioTracks()
        if (tracks.isEmpty()) {
            showPlayerMessage(org.skepsun.kototoro.R.string.video_no_audio_tracks)
            return
        }
        val labels = tracks.map { it.displayName() }.toTypedArray()
        val checked = tracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        showSelectionDialog(R.string.video_audio_track, labels.asList(), checked) { which ->
            player.setAudioTrack(tracks[which].id)
        }
    }


    fun showQualityDialog() {
        val videoSnapshot = availableVideos
        if (videoSnapshot.isEmpty()) {
            showPlayerMessage(org.skepsun.kototoro.R.string.operation_not_supported)
            return
        }
        val titles = videoSnapshot.mapIndexed { index, video ->
            video.sourceDisplayLabel(index)
        }.toTypedArray()
        val selected = currentVideoIndex.coerceIn(0, titles.lastIndex)
        showSelectionDialog(R.string.video_quality, titles.asList(), selected) { which ->
            val selectedVideo = videoSnapshot.getOrNull(which) ?: return@showSelectionDialog
            switchVideoQuality(selectedVideo)
        }
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf(
            R.string.video_aspect_ratio_fit,
            R.string.video_aspect_ratio_fill,
            R.string.video_aspect_ratio_16_9,
            R.string.video_aspect_ratio_4_3,
            R.string.video_aspect_ratio_stretch,
        )
        val labels = options.map(::getString).toTypedArray()
        val checked = appSettings.videoAspectRatio.coerceIn(0, options.lastIndex)
        showSelectionDialog(R.string.video_aspect_ratio, labels.asList(), checked) { which ->
            appSettings.videoAspectRatio = which
            applyAspectRatio()
        }
    }

    private fun showPlaybackSpeedDialog() {
        val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { "%.2fx".format(it) }.toTypedArray()
        val current = appSettings.videoPlaybackSpeed
        val checked = options.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        showSelectionDialog(R.string.video_playback_speed, labels.asList(), checked) { which ->
            val speed = options[which]
            appSettings.videoPlaybackSpeed = speed
            applyPlaybackSpeed(speed)
            updatePlaybackSpeedButton()
        }
    }

    private fun showDefaultPlaybackSpeedDialog() {
        val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { "%.2fx".format(it) }.toTypedArray()
        val current = appSettings.videoDefaultSpeed
        val checked = options.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        showSelectionDialog(R.string.video_default_speed, labels.asList(), checked) { which ->
            appSettings.videoDefaultSpeed = options[which]
        }
    }

    private fun showSeekIntervalDialog(
        titleRes: Int,
        currentMs: Int,
        onSelect: (Int) -> Unit,
    ) {
        val options = listOf(5, 10, 15, 30)
        val labels = options.map { "${it}s" }.toTypedArray()
        val checked = options.indexOfFirst { it * 1000 == currentMs }
            .takeIf { it >= 0 } ?: 1
        showSelectionDialog(titleRes, labels.asList(), checked) { which ->
            onSelect(options[which] * 1000)
        }
    }

    private fun showSelectionDialog(
        titleRes: Int,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        selectionDialogState = VideoSelectionDialogState(
            title = getString(titleRes),
            options = options,
            selectedIndex = selectedIndex,
            anchorBounds = submenuAnchorBounds,
            placement = submenuPlacement,
            onSelect = onSelect,
        )
    }

    private fun switchVideoQuality(
        selection: Video,
        resumeMs: Long = videoPlayer?.positionMs ?: 0L,
    ) {
        val resolved = availableVideos.resolveCloudstreamVideo(selection)
        if (resolved == null) {
            Log.d("VideoPlayer", "Ignoring stale video selection url=${selection.videoUrl}")
            return
        }
        val video = resolved.value
        currentVideoIndex = resolved.index
        updateQualityButtonLabel()
        pendingExternalSubtitles = video.subtitleTracks
        pendingExternalAudio = video.audioTracks
        val repo = currentVideoSource?.let { src -> mangaRepositoryFactory.create(src) }
        val mergedHeaders = if (currentVideoSource is CloudstreamSource) {
            headersToMap(video.headers)
        } else {
            mergeHeaders(repo?.getRequestHeaders(), headersToMap(video.headers))
        }
        startPlayback(
            url = video.videoUrl,
            source = currentVideoSource,
            headers = mergedHeaders,
            startMs = resumeMs,
            isTorrent = video.internalData == TORRENT_VIDEO_MARKER || video.videoUrl.isTorrentLocator(),
            forceHls = video.internalData == HLS_VIDEO_MARKER,
        )
    }

    private fun showVideoSuperResolutionSheet() {
        superResolutionDialogVisible = true
    }

    private fun buildSuperResolutionDialogState(): VideoSuperResolutionDialogState {
        return VideoSuperResolutionDialogState(
            enabled = enhancementSessionEnabled,
            algorithm = appSettings.videoEnhancementAlgorithm,
            anime4KPreset = appSettings.videoAnime4KPreset,
            fsrSharpnessPercent = (appSettings.videoFsrSharpness * 100f).roundToInt(),
            rememberAcrossVideos = appSettings.videoEnhancementRememberAcrossVideos,
            anchorBounds = submenuAnchorBounds,
        )
    }

    private fun setVideoEnhancementEnabled(enabled: Boolean) {
        Log.i(
            "VideoPlayerActivity",
            "Video enhancement toggled enabled=$enabled surfaceReady=$enhancementSurfaceReady " +
                "viewReady=${::enhancementView.isInitialized}",
        )
        enhancementSessionEnabled = enabled
        if (appSettings.videoEnhancementRememberAcrossVideos) {
            appSettings.videoEnhancementRememberedEnabled = enabled
        }
        if (enabled) applySuperResolutionFromSettings() else fallbackToDirectOutput()
        superResolutionDialogVersion++
    }

    private fun selectVideoEnhancementAlgorithm(algorithm: VideoEnhancementAlgorithm) {
        if (appSettings.videoEnhancementAlgorithm == algorithm) return
        appSettings.videoEnhancementAlgorithm = algorithm
        applySuperResolutionFromSettings()
        superResolutionDialogVersion++
    }

    private fun selectAnime4KPreset(preset: Anime4KPreset) {
        if (appSettings.videoAnime4KPreset == preset) return
        appSettings.videoAnime4KPreset = preset
        applySuperResolutionFromSettings()
        superResolutionDialogVersion++
    }

    private fun setFsrSharpness(percent: Int) {
        val sharpness = percent.coerceIn(0, 100) / 100f
        if (appSettings.videoFsrSharpness == sharpness) return
        appSettings.videoFsrSharpness = sharpness
        applySuperResolutionFromSettings()
        superResolutionDialogVersion++
    }

    private fun setRememberVideoEnhancement(remember: Boolean) {
        appSettings.videoEnhancementRememberAcrossVideos = remember
        appSettings.videoEnhancementRememberedEnabled = remember && enhancementSessionEnabled
        superResolutionDialogVersion++
    }

    private fun updatePlaybackSpeedButton() {
        syncComposeControlState()
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStart() {
        super.onStart()
        torrentStreamService.resume(currentMediaUrl)
    }

    override fun onResume() {
        super.onResume()
        if (::enhancementView.isInitialized) enhancementView.resumeVideoSurface()
    }

    override fun onPause() {
        if (::enhancementView.isInitialized) enhancementView.pauseVideoSurface()
        super.onPause()
    }

    override fun onStop() {
        playerRoot.removeCallbacks(hideUiRunnable)
        playerRoot.removeCallbacks(progressUpdateRunnable)
        playerRoot.removeCallbacks(controllerProgressRunnable)
        playerRoot.removeCallbacks(progressSaveRunnable)
        stopLongSeek()
        super.onStop()
        // 保存当前播放进度（本地与历史?
        savePlaybackProgress()
        saveHistoryProgressAsync()
        finishReadingSession()
        videoLocalCacheProxy.logSessionStats("onStop")
        videoPlayer?.pause()
        torrentStreamService.pause(currentMediaUrl)
        setKeepScreenOn(false)
        danmakuController.pause()
    }

    override fun onDestroy() {
        cloudstreamPlaybackInstance++
        cloudstreamLinkJob?.cancel()
        cloudstreamLinkJob = null
        cloudstreamFallbackJob?.cancel()
        cloudstreamFallbackJob = null
        externalTrackLoadingJob?.cancel()
        externalTrackLoadingJob = null
        torrentResolutionJob?.cancel()
        torrentResolutionJob = null
        playerRoot.removeCallbacks(hideUiRunnable)
        playerRoot.removeCallbacks(progressUpdateRunnable)
        playerRoot.removeCallbacks(controllerProgressRunnable)
        playerRoot.removeCallbacks(progressSaveRunnable)
        stopLongSeek()
        // 兜底保存进度（本地与历史?
        savePlaybackProgress()
        saveHistoryProgressAsync()
        finishReadingSession()
        videoPlayer?.release()
        videoPlayer = null
        torrentStreamService.release(currentMediaUrl)
        playerView.player = null
        if (::enhancementView.isInitialized) enhancementView.releaseVideoSurface()
        enhancementVideoSurface = null
        danmakuController.release()
        super.onDestroy()
    }

    fun applyDanmakuSettings() {
        val settings = DanmakuSettings(
            enabled = appSettings.videoDanmakuEnabled,
            sizePercent = appSettings.videoDanmakuSizePercent,
            speedPercent = appSettings.videoDanmakuSpeedPercent,
            opacityPercent = appSettings.videoDanmakuOpacityPercent,
            strokePercent = appSettings.videoDanmakuStrokePercent,
            showScroll = appSettings.videoDanmakuShowScroll,
            showTop = appSettings.videoDanmakuShowTop,
            showBottom = appSettings.videoDanmakuShowBottom,
            maxScrollLines = appSettings.videoDanmakuMaxScrollLines,
            maxTopLines = appSettings.videoDanmakuMaxTopLines,
            maxBottomLines = appSettings.videoDanmakuMaxBottomLines,
            maxScreenNum = appSettings.videoDanmakuMaxScreenNum,
        )
        danmakuController.applySettings(settings)
        if (!settings.enabled) {
            danmakuController.setVisible(false)
        } else {
            if (danmakuController.isPrepared()) {
                danmakuController.setVisible(true)
            } else {
                danmakuKey = null
                maybeLoadDanmaku()
            }
        }
    }

    fun applyPlaybackSpeed(speed: Float) {
        videoPlayer?.setRate(speed.toDouble())
    }

    fun applyPlaybackOptions() {
        videoPlayer?.setVolume(100.0)
        videoPlayer?.setVolumeBoost(appSettings.videoVolumeBoostEnabled)
    }

    fun applyAspectRatio() {
        videoPlayer?.setAspectRatio(appSettings.videoAspectRatio)
    }

    fun reloadPlayback() {
        val manga = currentMangaContent()
        val chapter = manga?.chapters?.find { it.id == currentReaderStateOrIntent()?.chapterId }
        if (manga?.source is CloudstreamSource && chapter != null) {
            val resumeMs = videoPlayer?.positionMs ?: 0L
            lifecycleScope.launch {
                val repo = mangaRepositoryFactory.create(manga.source) as? CloudstreamContentRepository
                val started = repo?.let {
                    loadAndPlayCloudstreamChapter(
                        repo = it,
                        chapter = chapter,
                        source = manga.source,
                        startMs = resumeMs,
                        clearCache = true,
                    )
                } == true
                if (!started) showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            }
            return
        }
        val url = currentMediaUrl
        if (url.isNullOrBlank()) {
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            return
        }
        val resumeMs = videoPlayer?.positionMs ?: 0L
        startPlayback(
            url = url,
            source = currentVideoSource,
            headers = currentMediaHeaders,
            startMs = resumeMs,
            forceHls = currentMediaForceHls,
        )
    }

    private fun openVideoDetails() {
        videoInfoDialogText = buildVideoDetailsText()
    }

    private fun buildVideoDetailsText(): String {
        fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "${bytes} B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }

        val (title, chapter) = extractChapterInfo()
        val decoderSetting = "MediaCodec (fallback enabled)"
        val rendererSetting = if (enhancementSessionEnabled) "Media3 + GLES" else "Media3 PlayerView"
        val hwdecCurrent = videoPlayer?.getPropertyString("hwdec-current").orDash()
        val voCurrent = videoPlayer?.getPropertyString("vo").orDash()
        val videoCodec = videoPlayer?.getPropertyString("video-codec").orDash()
        val audioCodec = videoPlayer?.getPropertyString("audio-codec-name").orDash()
        val videoWidth = videoPlayer?.getPropertyString("video-params/w").orDash()
        val videoHeight = videoPlayer?.getPropertyString("video-params/h").orDash()
        val fps = (
            videoPlayer?.getPropertyString("estimated-vf-fps")
                ?: videoPlayer?.getPropertyString("video-params/fps")
                ?: videoPlayer?.getPropertyString("container-fps")
            ).orDash()
        val sourceName = currentVideoSource?.name.orDash()
        val proxyStats = videoLocalCacheProxy.getSessionStatsSnapshot()
        val diagnostics = playbackDiagnostics.snapshot()
        val effectiveRendererSetting = rendererSetting
        val lastFailureCategory = diagnostics.lastFailureCategory?.name.orDash()
        val lastFallbackReason = diagnostics.lastFallbackReason?.name.orDash()

        val resolution = if (videoWidth != "-" && videoHeight != "-") {
            "${videoWidth}x${videoHeight}"
        } else {
            "-"
        }

        return buildString {
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_title, title.ifBlank { "-" }))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_chapter, chapter.ifBlank { "-" }))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_source, sourceName))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_url, currentMediaUrl.orDash()))
            appendLine()
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_decoding_setting, decoderSetting))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_current_decoder, hwdecCurrent))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_renderer_setting, rendererSetting))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_current_renderer, voCurrent))
            appendLine()
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_video_codec, videoCodec))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_audio_codec, audioCodec))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_resolution, resolution))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_fps, fps))
            appendLine()
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_proxy_stats))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_hits, proxyStats.hit))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_misses, proxyStats.miss))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_writes, proxyStats.writeCount))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_write_bytes, formatBytes(proxyStats.writeBytes)))
            appendLine()
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_playback_diagnostics))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_device_tier, devicePerformanceInfo.tier.name))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_effective_renderer, effectiveRendererSetting))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_effective_super_res, effectivePlaybackConfig.superResolutionMode.name))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_startup_timeouts, diagnostics.startupTimeoutCount))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_fallback_count, diagnostics.fallbackCount))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_network_error_count, diagnostics.networkOrSourceErrorCount))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_compat_error_count, diagnostics.compatibilityErrorCount))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_unknown_error_count, diagnostics.unknownErrorCount))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_last_failure_category, lastFailureCategory))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_last_failure_trigger, diagnostics.lastFailureTrigger.orDash()))
            appendLine(getString(org.skepsun.kototoro.R.string.video_info_last_fallback_reason, lastFallbackReason))
            append(getString(org.skepsun.kototoro.R.string.video_info_last_failure_detail, diagnostics.lastFailureDetail.orDash()))
        }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val pm = packageManager
        if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            showPlayerMessage(org.skepsun.kototoro.R.string.operation_not_supported)
            return
        }
        setUiIsVisible(false)
        val paramsBuilder = PictureInPictureParams.Builder()
        val pipWidth = videoPlayer?.getPropertyString("video-params/w")?.toIntOrNull()
        val pipHeight = videoPlayer?.getPropertyString("video-params/h")?.toIntOrNull()
        if (pipWidth != null && pipHeight != null && pipWidth > 0 && pipHeight > 0) {
            paramsBuilder.setAspectRatio(Rational(pipWidth, pipHeight))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            paramsBuilder.setSeamlessResizeEnabled(false)
        }
        enterPictureInPictureMode(paramsBuilder.build())
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            setUiIsVisible(false)
        }
    }

    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val surfaceView = playerView.videoSurfaceView as? android.view.SurfaceView
        if (surfaceView == null) {
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            return
        }
        if (surfaceView.width <= 0 || surfaceView.height <= 0) {
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            return
        }
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            surfaceView,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    saveBitmapToGallery(bitmap)
                } else {
                    showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "kototoro_${System.currentTimeMillis()}.png"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kototoro")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        showPlayerMessage(org.skepsun.kototoro.R.string.saved)
    }

    private fun maybeLoadDanmaku() {
        if (!appSettings.videoDanmakuEnabled) {
            android.util.Log.d("Danmaku", "Danmaku disabled by settings; keep loading in background")
        }
        val manga = currentMangaContent()
        val title = manga?.title?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(AppRouter.KEY_TITLE)
            ?: run {
                android.util.Log.d("Danmaku", "Danmaku skipped: missing title")
                return
            }
        val cacheKey = buildDanmakuCacheKey(manga?.id, title)
        val keywords = buildDanmakuKeywords(manga, title)
        val episode = resolveEpisodeNumber(manga?.chapters.orEmpty())
        if (episode <= 0) {
            android.util.Log.d("Danmaku", "Danmaku skipped: episode=$episode title=$title")
            return
        }
        val url = currentMediaUrl ?: ""
        val key = "$title#$episode#$url"
        if (key == danmakuKey) {
            android.util.Log.d("Danmaku", "Danmaku cache hit: key=$key")
            return
        }
        danmakuKey = key
        danmakuController.clear()
        danmakuLoadJob?.cancel()
        danmakuLoadJob = lifecycleScope.launch {
            android.util.Log.d(
                "Danmaku",
                "Load start: title=$title episode=$episode url=$url filters=dandan:${appSettings.videoDanmakuSourceDanDan} bili:${appSettings.videoDanmakuSourceBilibili} qq:${appSettings.videoDanmakuSourceQq}",
            )
            val items = loadDanmakuFromSources(title, episode, url, cacheKey, keywords)
            if (items.isEmpty()) {
                android.util.Log.d("Danmaku", "Load result: empty")
                danmakuController.setVisible(false)
                return@launch
            }
            android.util.Log.d("Danmaku", "Load result: ${items.size} items")
            val autoShow = appSettings.videoDanmakuEnabled
            danmakuController.loadDanmaku(
                items = items,
                autoShow = autoShow,
                isPlaying = videoPlayer?.isPlaying == true,
            )
            danmakuController.setVisible(autoShow)
        }
    }

    private suspend fun loadDanmakuFromSources(
        title: String,
        episode: Int,
        url: String,
        cacheKey: String,
        keywords: List<String>,
    ): List<org.skepsun.kototoro.video.danmaku.DanmakuItem> {
        return danmakuSourceManager.loadFromSources(
            title = title,
            episode = episode,
            url = url,
            cacheKey = cacheKey,
            keywords = keywords,
            enableDanDan = appSettings.videoDanmakuSourceDanDan,
            enableBilibili = appSettings.videoDanmakuSourceBilibili,
            enableQq = appSettings.videoDanmakuSourceQq,
        )
    }

    private fun buildDanmakuCacheKey(mangaId: Long?, title: String): String {
        val idPart = mangaId?.takeIf { it > 0 }?.toString()
        return idPart ?: title.trim()
    }

    private fun currentMangaContent(): org.skepsun.kototoro.parsers.model.Content? {
        return mangaContent
    }

    private fun buildDanmakuKeywords(
        manga: org.skepsun.kototoro.parsers.model.Content?,
        title: String,
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        candidates.add(title)
        manga?.altTitles?.forEach { alt: String ->
            if (alt.isNotBlank()) candidates.add(alt)
        }
        val sanitized = candidates.flatMap { keywordVariants(it) }
        return sanitized.distinct().filter { it.isNotBlank() }
    }

    private fun keywordVariants(title: String): List<String> {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return emptyList()
        val removeBrackets = trimmed.replace(Regex("[\\[\\(（【].*?[\\]）】]"), "")
        val noPunct = removeBrackets.replace(Regex("[\\s\\p{Punct}！？。、《》“”‘’·]"), "")
        return listOf(trimmed, removeBrackets, noPunct).distinct()
    }

    private fun resolveEpisodeNumber(chapters: List<ContentChapter>): Int {
        val chapter = if (chapters.isNotEmpty()) {
            val currentId = readerState?.chapterId ?: chapters.first().id
            chapters.firstOrNull { it.id == currentId } ?: chapters.first()
        } else {
            null
        }
        val number = chapter?.number ?: 0f
        if (number > 0f) {
            return number.roundToInt()
        }
        val title = chapter?.title
            ?: extractChapterInfo().second.takeIf { it.isNotBlank() }
            ?: return 0
        val match = Regex("(\\d+)").find(title) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private fun sendLocalDanmaku(message: String) {
        if (!appSettings.videoDanmakuEnabled) {
            showPlayerMessage(org.skepsun.kototoro.R.string.video_danmaku_enabled)
            return
        }
        val timeMs = videoPlayer?.positionMs ?: return
        danmakuController.addLiveDanmaku(message, timeMs)
    }

    private fun savePlaybackProgress(
        completed: Boolean = false,
        propagateFailure: Boolean = false,
    ) {
        val currentUrl = currentMediaUrl
        val player = videoPlayer
        val dur = videoPlayer?.durationMs
        if (currentUrl == null || player == null || dur == null) {
            if (propagateFailure) error("Playback state is not ready")
            return
        }
        val pos = if (completed && dur > 0L) dur else player.positionMs
        val result = runCatching {
            check(
                getSharedPreferences("video_progress", MODE_PRIVATE)
                .edit()
                .putLong(currentUrl, pos)
                .putLong("${currentUrl}_duration", dur)
                .putLong("${currentUrl}_timestamp", System.currentTimeMillis())
                .commit(),
            )
        }.onFailure { e ->
            android.util.Log.e("VideoPlayer", "Failed to save progress", e)
        }
        if (propagateFailure) result.getOrThrow()
    }

    private fun restorePlaybackProgress() {
        val currentUrl = currentMediaUrl ?: return
        val prefs = getSharedPreferences("video_progress", MODE_PRIVATE)
        val pos = prefs.getLong(currentUrl, 0L)
        val dur = prefs.getLong("${currentUrl}_duration", 0L)
        if (pos <= 0L) return
        if (dur > 0L && pos >= (dur - 2_000L)) {
            android.util.Log.d("VideoPlayer", "Skip restore: near end pos=$pos dur=$dur")
            return
        }
        videoPlayer?.seekTo(pos)
    }

    private fun resolveSavedPlaybackProgress(url: String): Long? {
        val prefs = getSharedPreferences("video_progress", MODE_PRIVATE)
        val pos = prefs.getLong(url, 0L)
        val dur = prefs.getLong("${url}_duration", 0L)
        if (pos <= 0L) return null
        if (dur > 0L && pos >= (dur - 2_000L)) return null
        return pos
    }

    private suspend fun restoreInitialSeekPercentFromHistory() {
        val manga = currentMangaContent() ?: return
        val history = runCatching { historyRepository.getOne(manga) }.getOrNull() ?: return
        android.util.Log.d("VideoPlayer", "Restore history: chapterId=${history.chapterId}, percent=${history.percent}")
        
        // Get current chapter ID from ReaderState or intent
        val currentState = currentReaderStateOrIntent()
        val currentChapterId = currentState?.chapterId
        
        android.util.Log.d("VideoPlayer", "Current chapter ID from intent/state: $currentChapterId")
        
        // Verify chapter ID matches current playing chapter
        if (currentChapterId != null && currentChapterId != history.chapterId) {
            android.util.Log.d("VideoPlayer", "Chapter mismatch: history has ${history.chapterId}, but playing ${currentChapterId}. Not restoring position.")
            // Don't restore position when chapter doesn't match
            return
        }
        
        val overall = history.percent
        if (overall !in 0f..1f) {
            android.util.Log.d("VideoPlayer", "Invalid history percent: $overall")
            return
        }
        if (overall >= 0.98f) {
            android.util.Log.d("VideoPlayer", "Skip history seek: overall=$overall")
            return
        }
        
        val chapters = manga.chapters ?: run {
            // 无章节信息时无法拆分整体百分比，直接使用整体值（退化为单集?
            android.util.Log.d("VideoPlayer", "No chapters, using overall percent: $overall")
            pendingInitialSeekPercent = overall
            return
        }
        
        val chapter = chapters.find { it.id == history.chapterId } ?: run {
            android.util.Log.d("VideoPlayer", "Chapter not found for id=${history.chapterId}, using overall percent")
            pendingInitialSeekPercent = overall
            return
        }
        
        android.util.Log.d("VideoPlayer", "Found chapter: ${chapter.title} (id=${chapter.id})")
        
        val branchChapters = chapters.filter { it.branch == chapter.branch }
        val count = branchChapters.size
        if (count <= 0) {
            android.util.Log.d("VideoPlayer", "No chapters in branch '${chapter.branch}'")
            pendingInitialSeekPercent = overall
            return
        }
        val idx = branchChapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
        // 单集百分?= 整体百分?* 总集?- 当前集索?
        val episodePercent = (overall * count - idx).coerceIn(0f, 1f)
        android.util.Log.d("VideoPlayer", "Calculated episode percent: $episodePercent (idx=$idx, count=$count, overall=$overall)")
        pendingInitialSeekPercent = episodePercent
    }

    private fun tryApplyInitialSeek() {
        val p = pendingInitialSeekPercent ?: return
        if (p >= 0.98f) {
            android.util.Log.d("VideoPlayer", "Skip initial seek: percent=$p")
            pendingInitialSeekPercent = null
            return
        }
        val pos = videoPlayer?.positionMs ?: 0L
        if (pos > 0L) {
            pendingInitialSeekPercent = null
            return
        }
        val dur = videoPlayer?.durationMs ?: 0L
        if (dur > 0) {
            videoPlayer?.seekTo((p * dur).toLong())
            pendingInitialSeekPercent = null
        }
    }

    private fun saveHistoryProgressAsync(
        completed: Boolean = false,
        requireHistory: Boolean = false,
    ): Job? {
        val exo = videoPlayer ?: return null
        val mangaSeed = currentMangaContent() ?: return null
        val dur = exo.durationMs
        val pos = exo.positionMs
        // 当时长未知（直播或刚开始播放）时，也保存一个有效百分比以建立历史记?
        val episodePercent = if (completed) {
            1f
        } else if (dur > 0) {
            (pos.toFloat() / dur).coerceIn(0f, 1f)
        } else 0f

        android.util.Log.d("VideoPlayer", "Save progress: pos=$pos, dur=$dur, episodePercent=$episodePercent")

        // Ensure ReaderState reflects current chapter before saving
        val state = readerState
        android.util.Log.d("VideoPlayer", "ReaderState before save: chapterId=${state?.chapterId}, page=${state?.page}")
        
        if (state == null) {
            android.util.Log.d("VideoPlayer", "ReaderState is null, cannot save accurate chapter progress")
        }

        fun computeSeriesPercent(m: org.skepsun.kototoro.parsers.model.Content, s: ReaderState, ep: Float): Float {
            val chapters = m.chapters ?: run {
                android.util.Log.d("VideoPlayer", "No chapters available for series percent calculation")
                return ep
            }
            val curr = chapters.find { it.id == s.chapterId } ?: run {
                android.util.Log.d("VideoPlayer", "Current chapter (id=${s.chapterId}) not found in chapters list")
                return ep
            }
            val branchChapters = chapters.filter { it.branch == curr.branch }
            val count = branchChapters.size
            if (count <= 0) {
                android.util.Log.d("VideoPlayer", "No chapters in branch '${curr.branch}'")
                return ep
            }
            val idx = branchChapters.indexOfFirst { it.id == curr.id }.coerceAtLeast(0)
            val ppc = 1f / count
            val seriesPercent = (ppc * idx + ppc * ep).coerceIn(0f, 1f)
            android.util.Log.d("VideoPlayer", "Series percent calculation: chapter=${curr.title}, idx=$idx, count=$count, episodePercent=$ep, seriesPercent=$seriesPercent")
            return seriesPercent
        }

        // 其余部分需要加载详情以确保 chapters 非空
        return lifecycleScope.launch(CoroutineExceptionHandler { _, error ->
            android.util.Log.e("VideoPlayer", "History save job failed", error)
        }) {
            // 先确保漫画详情含章节
            // 防御性拦截：如果 mangaSeed ?URL 是本地文件协议，绝对不能交给在线解析器，否则必定抛错
	            val manga = if (mangaSeed.chapters.isNullOrEmpty()) {
	                if (mangaSeed.url.startsWith("file://")) {
	                    android.util.Log.d("VideoPlayer", "Cannot load details from source for local file URL: ${mangaSeed.url}")
	                    val dbContent = contentDataRepository.findPreferredLocalContentById(mangaSeed.id, withChapters = true)
	                        ?: contentDataRepository.findContentById(mangaSeed.id, withChapters = true)
	                    dbContent ?: mangaSeed
	                } else {
                    val repo = mangaRepositoryFactory.create(mangaSeed.source)
                    runCatching { repo.getDetails(mangaSeed) }.getOrDefault(mangaSeed)
                }
            } else {
                mangaSeed
            }
            
            // 若仍无章节信息（网络/源不可用），避免保存触发断言失败
            if (manga.chapters.isNullOrEmpty()) {
                android.util.Log.d("VideoPlayer", "Cannot save history: manga has no chapters")
                if (requireHistory) error("Cannot save history without chapters")
                return@launch
            }

            if (state != null) {
                // Verify ReaderState chapter ID exists in manga chapters
                val chapterExists = manga.chapters?.any { it.id == state.chapterId } == true
                if (!chapterExists) {
                    android.util.Log.e("VideoPlayer", "ReaderState chapter ID ${state.chapterId} does not exist in manga chapters!")
                }
                
                // ReaderState 已提供：直接计算整体百分比并保存
                val overall = computeSeriesPercent(manga, state, episodePercent)
                android.util.Log.d("VideoPlayer", "Saving history with ReaderState: chapterId=${state.chapterId}, overall=$overall")
                val timedState = state.copy(
                    page = (if (completed && dur > 0L) dur else pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    scroll = episodePercentToScroll(episodePercent),
                )
                ensureReadingSession(timedState, overall)
                if (requireHistory) {
                    historyUpdateUseCase(manga, timedState, overall)
                } else {
                    historyUpdateUseCase.invokeAsync(manga, timedState, overall)
                }
            } else {
                // ?ReaderState：优先使用已有历史，否则用首章构?
                val history = runCatching { historyRepository.getOne(manga) }.getOrNull()
                val fallbackState = history
                    ?.takeIf { hist -> manga.chapters?.any { it.id == hist.chapterId } == true }
                    ?.let { ReaderState(it) }
                    ?: runCatching { ReaderState(manga, null) }.getOrNull()
                if (fallbackState != null) {
                    android.util.Log.d("VideoPlayer", "Using fallback ReaderState: chapterId=${fallbackState.chapterId}")
                    val overall = computeSeriesPercent(manga, fallbackState, episodePercent)
                    val timedState = fallbackState.copy(
                        page = (if (completed && dur > 0L) dur else pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        scroll = episodePercentToScroll(episodePercent),
                    )
                    ensureReadingSession(timedState, overall)
                    if (requireHistory) {
                        historyUpdateUseCase(manga, timedState, overall)
                    } else {
                        historyUpdateUseCase.invokeAsync(manga, timedState, overall)
                    }
                } else {
                    android.util.Log.d("VideoPlayer", "Cannot create fallback ReaderState")
                    if (requireHistory) error("Cannot create history state")
                }
            }
        }
    }

    private suspend fun flushForSpaceSwitch() {
        savePlaybackProgress(propagateFailure = true)
        val historyJob = saveHistoryProgressAsync(requireHistory = true)
            ?: error("Playback history is not ready")
        historyJob.awaitCompletion()
        finishReadingSession(allowShort = true, continueFromEnd = false)?.awaitCompletion()
        videoPlayer?.pause()
        danmakuController.pause()
    }

    private fun episodePercentToScroll(percent: Float): Int {
        return (percent.coerceIn(0f, 1f) * 10000).toInt()
    }

    private fun currentVideoRecordState(): ReaderState? {
        val state = readerState ?: return null
        val pos = videoPlayer?.positionMs ?: 0L
        val dur = videoPlayer?.durationMs ?: 0L
        val episodePercent = if (dur > 0L) {
            (pos.toFloat() / dur).coerceIn(0f, 1f)
        } else {
            0f
        }
        return state.copy(
            page = pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            scroll = episodePercentToScroll(episodePercent),
        )
    }

    private fun ensureReadingSession(state: ReaderState, percent: Float) {
        if (sessionStartState != null) return
        sessionStartAt = System.currentTimeMillis()
        sessionStartState = state
        sessionStartPercent = percent
    }

    private fun finishReadingSession(
        allowShort: Boolean = false,
        continueFromEnd: Boolean = true,
    ): Job? {
        val manga = currentMangaContent() ?: return null
        if (readingRecordRepository.shouldSkip(manga)) return null
        val startState = sessionStartState ?: currentVideoRecordState() ?: return null
        val endState = currentVideoRecordState() ?: startState
        val startAt = sessionStartAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val endAt = System.currentTimeMillis()
        val startPercent = sessionStartPercent
        val endPercent = computeVideoSeriesPercent(manga, endState)
        if (continueFromEnd) {
            sessionStartAt = endAt
            sessionStartState = endState
            sessionStartPercent = endPercent
        } else {
            sessionStartAt = 0L
            sessionStartState = null
            sessionStartPercent = 0f
        }
        return lifecycleScope.launch(
            Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                android.util.Log.e("VideoPlayer", "Reading record save failed", error)
            },
        ) {
            readingRecordRepository.recordSession(
                manga = manga,
                startAt = startAt,
                endAt = endAt,
                startState = startState,
                startPercent = startPercent,
                endState = endState,
                endPercent = endPercent,
                allowShort = allowShort,
            )
        }
    }

    private fun recordVideoJumpPoint(
        fromState: ReaderState?,
        toState: ReaderState,
        source: String,
        force: Boolean = false,
    ) {
        val manga = currentMangaContent() ?: return
        val from = fromState ?: return
        if (readingRecordRepository.shouldSkip(manga)) return
        if (!force && from.chapterId == toState.chapterId && kotlin.math.abs(from.page - toState.page) < 5_000) return
        lifecycleScope.launch(Dispatchers.Default) {
            readingRecordRepository.recordJumpPoint(
                manga = manga,
                fromState = from,
                fromPercent = computeVideoSeriesPercent(manga, from),
                toState = toState,
                toPercent = computeVideoSeriesPercent(manga, toState),
                source = source,
            )
        }
    }

    private fun computeVideoSeriesPercent(manga: Content, state: ReaderState): Float {
        val chapters = manga.chapters.orEmpty()
        val episodePercent = (state.scroll / 10000f).coerceIn(0f, 1f)
        if (chapters.isEmpty()) return episodePercent
        val current = chapters.find { it.id == state.chapterId } ?: return episodePercent
        val branchChapters = chapters.filter { it.branch == current.branch }
        if (branchChapters.isEmpty()) return episodePercent
        val index = branchChapters.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val perChapter = 1f / branchChapters.size
        return (perChapter * index + perChapter * episodePercent).coerceIn(0f, 1f)
    }

    // ReaderNavigationCallback implementation
    override fun onPageSelected(page: ReaderPage): Boolean {
        // Video player doesn't support page-level navigation
        return false
    }

    override fun onChapterSelected(chapter: ContentChapter): Boolean {
        // Handle chapter selection from the shared chapters/pages Compose content.
        val manga = currentMangaContent()
            ?: return false
        
        android.util.Log.d("VideoPlayer", "Chapter selected: ${chapter.title} (id=${chapter.id})")
        cloudstreamPlaybackInstance++
        cloudstreamLinkJob?.cancel()
        cloudstreamLinkJob = null
        
        // Save current progress before switching
        val previousState = currentVideoRecordState()
        savePlaybackProgress()
        saveHistoryProgressAsync()
        
        // Find the new chapter's video URL asynchronously
        lifecycleScope.launch {
            try {
                val repo = mangaRepositoryFactory.create(manga.source)
                var resolved = false
                val resetChapterState = {
                    finishReadingSession(allowShort = true, continueFromEnd = false)
                    readerState = ReaderState(chapter.id, 0, 0)
                    recordVideoJumpPoint(previousState, ReaderState(chapter.id, 0, 0), "chapter_list", force = true)
                    chaptersViewModel.setCurrentChapter(chapter)
                    hasSkippedIntro = false
                    hasTriggeredOutro = false
                    hasRestoredProgress = false
                    updateChapterNavButtons()
                }

                val localUrl = resolveLocalVideoUrl(manga, ReaderState(chapter.id, 0, 0), chapter.url)
                if (localUrl != null) {
                    availableVideos = emptyList()
                    currentVideoIndex = 0
                    updateQualityButtonVisibility()
                    currentVideoSource = manga.source
                    pendingExternalSubtitles = emptyList()
                    pendingExternalAudio = emptyList()
                    resetChapterState()
                    prepareAndPlay(localUrl, manga.source, headers = null)
                    updateTitleAndSubtitle()
                    resolved = true
                }
                
                // Try AniyomiAnimeRepository first (most video sources)
                if (!resolved && repo is AniyomiAnimeRepository) {
                    val videos = runCatching {
                        repo.getVideoListForChapter(chapter)
                            .filter { it.videoUrl.isNotBlank() }
                    }.getOrNull()
                    
                    if (!videos.isNullOrEmpty()) {
                        availableVideos = videos
                        updateQualityButtonVisibility()
                        currentVideoSource = manga.source
                        currentVideoIndex = videos.indexOfFirst { it.preferred }
                            .takeIf { it >= 0 } ?: 0
                        val selected = videos[currentVideoIndex]
                        val mergedHeaders = mergeHeaders(repo.getRequestHeaders(), headersToMap(selected.headers))
                        pendingExternalSubtitles = selected.subtitleTracks
                        pendingExternalAudio = selected.audioTracks
                        
                        resetChapterState()
                        
                        startPlayback(selected.videoUrl, manga.source, mergedHeaders)
                        updateTitleAndSubtitle()
                        resolved = true
                    }
                }

                if (!resolved && repo is CloudstreamContentRepository) {
                    resolved = loadAndPlayCloudstreamChapter(
                        repo = repo,
                        chapter = chapter,
                        source = manga.source,
                        onFirstVideo = resetChapterState,
                    )
                    if (resolved) updateTitleAndSubtitle()
                }
                
                // Fallback to getPages for non-Aniyomi sources
                if (!resolved && repo !is CloudstreamContentRepository) {
                    val pages = repo.getPages(chapter)
                    val fallbackVideos = pages.toFallbackVideos(repo)
                    if (fallbackVideos.isNotEmpty()) {
                        availableVideos = fallbackVideos
                        currentVideoIndex = 0
                        updateQualityButtonVisibility()
                        currentVideoSource = manga.source
                        val selected = fallbackVideos[currentVideoIndex]
                        pendingExternalSubtitles = selected.subtitleTracks
                        pendingExternalAudio = selected.audioTracks

                        resetChapterState()

                        val mergedHeaders = mergeHeaders(repo.getRequestHeaders(), headersToMap(selected.headers))
                        startPlayback(selected.videoUrl, manga.source, mergedHeaders)
                        updateTitleAndSubtitle()
                        resolved = true
                    }
                    val page = pages.firstOrNull()
                    val streamUrl = if (!resolved) page?.let { repo.getPageUrl(it) } else null
                    val streamHeaders = if (!resolved) page?.let { mergeHeaders(repo.getRequestHeaders(), it.headers) } else null
                    
                    if (streamUrl != null) {
                        Log.d(
                            "VideoPlayerActivity",
                            "Selected chapter page chapter=${chapter.id} url=$streamUrl headers=${streamHeaders?.keys} source=${manga.source.name}",
                        )
                        availableVideos = emptyList()
                        currentVideoIndex = 0
                        updateQualityButtonVisibility()
                        currentVideoSource = manga.source
                        
                        resetChapterState()
                        
                        prepareAndPlay(streamUrl, manga.source, streamHeaders)
                        updateTitleAndSubtitle()
                        resolved = true
                    }
                }
                
                if (!resolved) {
                    android.util.Log.w("VideoPlayer", "Failed to resolve stream URL for chapter ${chapter.id}")
                    showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "Error loading chapter", e)
                showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            }
        }
        
        return true // Indicate we handled the selection
    }

    private fun updateChapterNavButtons() {
        syncComposeControlState()
    }

    private fun navigateChapter(offset: Int) {
        val chapters = chaptersViewModel.chapters.value.map { it.chapter }.ifEmpty {
            currentMangaContent()?.chapters.orEmpty()
        }
        if (chapters.isEmpty()) return
        val currentId = readerState?.chapterId ?: chapters.first().id
        val currentIndex = chapters.indexOfFirst { it.id == currentId }
        if (currentIndex == -1) return
        val targetIndex = (currentIndex + offset).coerceIn(0, chapters.size - 1)
        if (targetIndex == currentIndex) return
        val targetChapter = chapters[targetIndex]
        onChapterSelected(targetChapter)
    }

	private fun maybeAutoPlayNext(ignoreRatio: Boolean = false) {
		if (!appSettings.videoAutoNextEnabled || autoNextTriggered) return
		val duration = videoPlayer?.durationMs ?: 0L
		val position = videoPlayer?.positionMs ?: 0L
		if (duration <= 0L) {
			android.util.Log.d("VideoPlayer", "AutoNext skipped: duration=0")
			return
		}
		val ratio = position.toDouble() / duration.toDouble()
		if (!ignoreRatio && ratio < 0.98) {
			android.util.Log.d("VideoPlayer", "AutoNext skipped: ratio=$ratio pos=$position dur=$duration")
			return
		}
		val manga = currentMangaContent() ?: return
		val chapters = manga.chapters ?: return
		if (chapters.isEmpty()) return
		val currentId = readerState?.chapterId ?: chapters.first().id
		val currentIndex = chapters.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return
		if (currentIndex < chapters.lastIndex) {
			android.util.Log.i("VideoPlayerActivity", "AutoNext successfully triggered. Navigating to index ${currentIndex + 1}.")
			autoNextTriggered = true
			navigateChapter(+1)
		}
	}

    override fun onBookmarkSelected(bookmark: Bookmark): Boolean {
        // Video player doesn't support bookmarks
        return false
    }

    private fun showSeekFeedback(posMs: Long, durationMs: Long, seekOffsetMs: Long) {
        val showHours = durationMs >= 3600_000L
        val timeStr = formatTimeMs(posMs, showHours) + " / " + formatTimeMs(durationMs, showHours)
        
        val offsetSec = (kotlin.math.abs(seekOffsetMs) / 1000).toInt()
        val deltaStr = if (seekOffsetMs > 0) {
            getString(org.skepsun.kototoro.R.string.video_fast_forward_time, offsetSec.toString())
        } else if (seekOffsetMs < 0) {
            getString(org.skepsun.kototoro.R.string.video_rewind_time, offsetSec.toString())
        } else {
            ""
        }
        
        seekFeedbackState = VideoSeekFeedbackState(
            text = if (deltaStr.isNotEmpty()) "$deltaStr\n$timeStr" else timeStr,
            progress = if (durationMs > 0) posMs.toFloat() / durationMs.toFloat() else 0f,
        )
    }

    private fun hideSeekFeedback() {
        seekFeedbackState = null
    }

    private fun openInExternalPlayer() {
        val url = currentMediaUrl
        if (url.isNullOrBlank()) {
            showPlayerMessage(R.string.no_video_loaded)
            return
        }
        val headers = currentMediaHeaders.orEmpty()
        val proxyUrl = videoLocalCacheProxy.getProxyUrl(url, headers, currentVideoSource)
        val title = composeControlState.title
        if (!ExternalPlayerHelper.openInExternalPlayer(this, proxyUrl, title)) {
            showPlayerMessage(R.string.no_external_player)
        }
    }

    private fun showDlnaDeviceSheet() {
        val url = currentMediaUrl
        if (url.isNullOrBlank()) {
            showPlayerMessage(R.string.no_video_loaded)
            return
        }
        dlnaDialogState = DlnaDeviceDialogState.Loading
        lifecycleScope.launch {
            val devices = SsdpDiscovery.discover(this@VideoPlayerActivity, contentHttpClient)
            if (dlnaDialogState != null) {
                dlnaDialogState = DlnaDeviceDialogState.Devices(devices)
            }
        }
    }

    private fun castToDlnaDevice(device: DlnaDevice) {
        val url = currentMediaUrl ?: return
        val headers = currentMediaHeaders.orEmpty()
        val positionMs = videoPlayer?.positionMs ?: 0L
        dlnaDialogState = DlnaDeviceDialogState.Casting(device)
        lifecycleScope.launch {
            val lanUrl = videoLocalCacheProxy.getLanProxyUrl(url, headers)
            if (lanUrl == null) {
                showPlayerMessage(R.string.cast_no_wifi)
                dlnaDialogState = null
                return@launch
            }
            val setOk = DlnaController.setAVTransportURI(contentHttpClient, device, lanUrl)
            if (setOk) {
                DlnaController.play(contentHttpClient, device)
                if (positionMs > 5000L) {
                    DlnaController.seek(contentHttpClient, device, positionMs)
                }
                showPlayerMessage(getString(R.string.casting_to, device.name))
                videoPlayer?.pause()
            } else {
                showPlayerMessage(R.string.cast_failed)
            }
            dlnaDialogState = null
        }
    }
}

internal fun isPlayerAdjustmentGestureStartAllowed(
    startY: Float,
    viewHeight: Int,
    topExclusion: Int,
    bottomExclusion: Int,
): Boolean {
    if (viewHeight <= 0) return false
    val top = topExclusion.coerceAtLeast(0)
    val bottom = viewHeight - bottomExclusion.coerceAtLeast(0)
    if (bottom <= top) return false
    return startY >= top && startY <= bottom
}

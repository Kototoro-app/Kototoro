package org.skepsun.kototoro.video.ui

import android.os.Bundle
import android.view.View
import android.content.res.Configuration
import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Build
import androidx.lifecycle.lifecycleScope
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.app.PictureInPictureParams
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.util.Log
import org.skepsun.kototoro.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
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
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource as ParsersContentSource
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
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
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.core.prefs.VideoRendererMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionShader
import org.skepsun.kototoro.video.player.CustomMpvView
import org.skepsun.kototoro.video.player.MpvPlayer
import org.skepsun.kototoro.video.player.MpvShaderManager
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy
import org.skepsun.kototoro.video.data.ExternalPlayerHelper
import org.skepsun.kototoro.video.performance.DevicePerformanceClassifier
import org.skepsun.kototoro.video.performance.DevicePerformanceInfo
import org.skepsun.kototoro.video.performance.EffectiveVideoPlaybackConfig
import org.skepsun.kototoro.video.performance.PlaybackFailureCategory
import org.skepsun.kototoro.video.performance.PlaybackFallbackController
import org.skepsun.kototoro.video.performance.PlaybackFallbackReason
import org.skepsun.kototoro.video.performance.PlaybackSessionDiagnostics
import org.skepsun.kototoro.video.performance.VideoPlaybackPolicy
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
import org.skepsun.kototoro.video.ui.compose.VideoChapterDialog
import org.skepsun.kototoro.video.ui.compose.VideoChapterDialogState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControls
import org.skepsun.kototoro.video.ui.compose.VideoPlayerInfoDialog
import org.skepsun.kototoro.video.ui.compose.VideoPlayerNativeInitErrorDialog
import org.skepsun.kototoro.video.ui.compose.PlayerMenuPlacement
import org.skepsun.kototoro.video.ui.compose.VideoSelectionDialog
import org.skepsun.kototoro.video.ui.compose.VideoSelectionDialogState
import org.skepsun.kototoro.video.ui.compose.VideoShaderOption
import org.skepsun.kototoro.video.ui.compose.VideoSuperResolutionDialog
import org.skepsun.kototoro.video.ui.compose.VideoSuperResolutionDialogState
import org.skepsun.kototoro.video.ui.compose.DlnaDeviceDialog
import org.skepsun.kototoro.video.ui.compose.DlnaDeviceDialogState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerRenderLayer

@AndroidEntryPoint
class VideoPlayerActivity : BaseComposeFullscreenActivity(), ReaderNavigationCallback {
    companion object {
        private const val ENABLE_M3U8_PROXY_CACHE = true
    }

    private data class PlayerOverflowAction(
        val title: String,
        val iconRes: Int,
        val onClick: () -> Unit,
    )

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
    private var suspiciousAdRetryCount = 0
    private val startupTimeoutMs = 8_000L

    private var mpvPlayer: MpvPlayer? = null
    internal fun getMpvPlayer(): MpvPlayer? = mpvPlayer
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
    private var currentVideoIndex: Int = 0
    private var currentVideoSource: ParsersContentSource? = null
    private var currentMediaHeaders: Map<String, String>? = null
    private var skipHistorySeekForCurrentMedia: Boolean = false
    private var pendingExternalSubtitles: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    private var pendingExternalAudio: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    private lateinit var mpvView: CustomMpvView
    private val mpvReady = CompletableDeferred<Boolean>()
    private var playerGestureInstaller: ((CustomMpvView) -> Unit)? = null
    private var playerGesturesInstalled = false
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
    lateinit var videoDownloadIndex: org.skepsun.kototoro.video.data.VideoDownloadIndex

    @Inject
    lateinit var downloadScheduler: DownloadWorker.Scheduler

    @Inject
    lateinit var videoLocalCacheProxy: VideoLocalCacheProxy

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
    private val mpvListener = object : MpvPlayer.Listener {
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
                updatePlaybackMenu()
                syncComposeControlState()
                danmakuController.onPlaybackStateChanged(isPlaying)
            }
        }

        override fun onPlaybackEnded() {
            val dur = mpvPlayer?.durationMs ?: 0L
            if (dur in 1L..90_000L && suspiciousAdRetryCount < 1) {
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
            savePlaybackProgress(completed = true)
            saveHistoryProgressAsync(completed = true)
            suspiciousAdRetryCount = 0
            runOnUiThread {
                maybeAutoPlayNext()
            }
        }

        override fun onFileLoaded() {
            runOnUiThread {
                hasCurrentMediaLoaded = true
                cancelPlaybackStartupTimeout()
                autoNextTriggered = false
                applySuperResolutionFromSettings()
                danmakuController.start()
                loadPendingExternalTracks()
                syncComposeControlState()
            }
        }

        override fun onPlaybackFailed(message: String?) {
            runOnUiThread {
                cancelPlaybackStartupTimeout()
                handlePlaybackFallback("mpv_end_file_before_loaded", message)
            }
        }

        override fun onSubtitleTextChanged(text: String?) {
            // This callback may or may not fire depending on mpv-android-lib version
            Log.d("VideoPlayerActivity", "onSubtitleTextChanged callback: '$text'")
            updateSubtitleOverlay(text)
        }

        override fun onPositionChanged(positionMs: Long) {
            // Poll sub-text every ~10th position update (~every 1s if updates come at ~100ms intervals)
            subtitlePollCounter++
            if (subtitlePollCounter % 10 == 0) {
                val text = mpvPlayer?.getPropertyString("sub-text")
                if (text != lastSubtitleTextFromPoll) {
                    lastSubtitleTextFromPoll = text
                    Log.d("VideoPlayerActivity", "sub-text poll: '$text'")
                    updateSubtitleOverlay(text)
                }
            }
            // Auto-skip outro: when position reaches outro start, seek to end
            if (outroStartMs > 0 && !hasTriggeredOutro && positionMs >= outroStartMs) {
                hasTriggeredOutro = true
                runOnUiThread {
                    showPlayerMessage(R.string.video_skipping_outro)
                    val dur = mpvPlayer?.durationMs ?: return@runOnUiThread
                    
                    if (appSettings.videoAutoNextEnabled) {
                        maybeAutoPlayNext(ignoreRatio = true)
                    }
                    if (!autoNextTriggered && dur > 0) {
                        // Seeking to exactly `dur` often fails or hits the wrong keyframe in mpv
                        // We do an EXACT seek to 500ms before duration, so it naturally hits EOF
                        mpvPlayer?.seekExact(dur - 500)
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
    private val playbackStartupTimeoutRunnable = Runnable {
        handlePlaybackStartupTimeout()
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
            val p = mpvPlayer ?: return
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

    private fun isLandscapeOrientation(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun installComposeContent() {
        setContent {
            KototoroTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayerRenderLayer(
                        onMpvViewCreated = ::onMpvViewCreated,
                        onDanmakuViewCreated = danmakuController::attach,
                        modifier = Modifier.fillMaxSize(),
                    )
                    VideoPlayerControls(
                        state = composeControlState,
                        onAction = ::onComposePlayerAction,
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
                                submenuAnchorBounds = itemBounds
                                submenuPlacement = PlayerMenuPlacement.BesideAnchor
                                item.onClick()
                                if (selectionDialogState == null && !superResolutionDialogVisible) {
                                    actionDialogState = null
                                }
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
                            onModeSelected = ::selectSuperResolutionMode,
                            onShaderSelected = ::selectSuperResolutionShader,
                            onCustomShaderToggled = ::toggleCustomSuperResolutionShader,
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

    private fun onMpvViewCreated(view: CustomMpvView) {
        if (::mpvView.isInitialized) return
        mpvView = view
        view.background = null
        val initialized = initializeMpvRuntime()
        mpvReady.complete(initialized)
        if (initialized) installPlayerGesturesIfReady()
    }

    private fun installPlayerGesturesIfReady() {
        if (playerGesturesInstalled || !::mpvView.isInitialized) return
        val installer = playerGestureInstaller ?: return
        playerGesturesInstalled = true
        installer(mpvView)
    }

    private fun onComposePlayerAction(action: VideoPlayerAction) {
        when (action) {
            VideoPlayerAction.NavigateBack -> finishAfterTransition()
            VideoPlayerAction.TogglePlayback -> mpvPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
            is VideoPlayerAction.SeekTo -> mpvPlayer?.seekTo(action.positionMs)
            is VideoPlayerAction.SeekBy -> mpvPlayer?.let { it.seekTo((it.positionMs + action.offsetMs).coerceIn(0L, it.durationMs)) }
            VideoPlayerAction.PreviousChapter -> navigateChapter(-1)
            VideoPlayerAction.NextChapter -> navigateChapter(1)
            is VideoPlayerAction.OpenSubtitleTracks -> {
                prepareDirectMenu(action.anchorBounds)
                showSubtitleTrackDialog()
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
            is VideoPlayerAction.OpenSettings -> showVideoSettingsPanel(action.anchorBounds)
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
        val player = mpvPlayer
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
            showChapterMarkers = isLandscapeOrientation(),
        )
    }

    private fun initializeMpvRuntime(): Boolean {
        return runCatching {
            mpvView.initialize(filesDir.path, cacheDir.path)
            mpvPlayer = MpvPlayer(mpvView.mpv).also { player ->
                player.initialize()
                player.addListener(mpvListener)
            }
            applySubtitleOverlayStyle()
        }.onFailure { error ->
            Log.e("VideoPlayerActivity", "Failed to initialize mpv runtime", error)
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
            positionProvider = { mpvPlayer?.positionMs ?: 0L },
            playingProvider = { mpvPlayer?.isPlaying == true },
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
            if (!mpvReady.await()) return@launch
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
                    val p = mpvPlayer
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
                    mpvPlayer?.let { p ->
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
                    val p = mpvPlayer ?: return
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
                            initialScrubPositionStart = mpvPlayer?.positionMs ?: 0L
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
                        val duration = mpvPlayer?.durationMs ?: return true
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
                            mpvPlayer?.setRate(originalSpeed)
                            isLongPressSpeeding = false
                        }
                        
                        // Action final horizontal scrub seek
                        if (isHorizontalScrubbing) {
                            mpvPlayer?.seekTo(lastScrubPosition)
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
        val mangaId = intent.getLongExtra(AppRouter.KEY_ID, -1L)
        if (mangaId > 0L) {
            contentDataRepository.findPreferredLocalContentById(mangaId, withChapters = true)?.let { current ->
                return current
            }
            contentDataRepository.findContentById(mangaId, withChapters = true)?.let { current ->
                return current
            }
        }
        return intent.getParcelableExtraCompat<ParcelableContent>(AppRouter.KEY_MANGA)?.manga
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
        availableVideos.getOrNull(currentVideoIndex)?.qualityDisplayLabel(currentVideoIndex)?.let {
            return it
        }
        return if (availableVideos.isNotEmpty()) {
            getString(org.skepsun.kototoro.R.string.video_quality_line, currentVideoIndex + 1)
        } else {
            getString(org.skepsun.kototoro.R.string.video_quality)
        }
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
        val lastSegment = runCatching { Uri.parse(normalizedUrl).lastPathSegment }.getOrNull() ?: normalizedUrl
        val lowerUrl = normalizedUrl.lowercase()
        val isHttpLike = lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")
        val isHtmlPlaybackPage = isHttpLike && TVBoxPlayback.looksLikeHtmlPlaybackPage(normalizedUrl)
        val isDirectPlaybackUrl = TVBoxPlayback.looksLikeDirectPlaybackUrl(normalizedUrl)
        val isDirectStream = lastSegment.endsWith(".m3u8", ignoreCase = true) ||
            lastSegment.endsWith(".mp4", ignoreCase = true) ||
            isDirectPlaybackUrl
        val isDirectLocator = lowerUrl.startsWith("magnet:") ||
            lowerUrl.startsWith("thunder:") ||
            lowerUrl.startsWith("ed2k:") ||
            lowerUrl.startsWith("ftp://") ||
            lowerUrl.startsWith("rtsp://") ||
            lowerUrl.startsWith("rtmp://") ||
            lowerUrl.startsWith("mms://")
        val isResolvedPlaybackUrl = isDirectStream || isDirectLocator || (isHttpLike && headers != null && !isHtmlPlaybackPage)
        val manga = currentMangaContent()
        val currentState = currentReaderStateOrIntent()
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
            var mpvUrl: String = localUrl
            if (localUrl.startsWith("file://")) {
                runCatching {
                    val decodedPath = Uri.parse(localUrl).path
                    if (decodedPath != null && File(decodedPath).exists()) {
                        mpvUrl = decodedPath
                    }
                }
            }
            
            startMpvPlayback(mpvUrl, manga?.source ?: source, headers = null, startMs = startMs)
            return
        }

        android.util.Log.d("VideoPlayer", "prepareAndPlay: url=$normalizedUrl, manga=${manga?.title}, chapters=${manga?.chapters?.size}, state=$currentState, isDirectStream=$isDirectStream")

        if (isHtmlPlaybackPage) {
            resolvePlaybackPageAndPlay(
                url = normalizedUrl,
                source = source,
                headers = headers,
            )
            return
        }

        if (isResolvedPlaybackUrl) {
            currentVideoSource = source
            availableVideos = emptyList()
            currentVideoIndex = 0
            updateQualityButtonVisibility()
            val mergedHeaders = if (headers.isNullOrEmpty() && source != null) {
                runCatching { mangaRepositoryFactory.create(source).getRequestHeaders() }.getOrDefault(emptyMap())
            } else {
                headers
            }
            if (lowerUrl.startsWith("magnet:") || lowerUrl.startsWith("thunder:") || lowerUrl.startsWith("ed2k:")) {
                android.util.Log.w("VideoPlayer", "Unsupported direct playback scheme: $url")
                showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred, SnackbarDuration.Long)
                return
            }
            startMpvPlayback(normalizedUrl, source, mergedHeaders, startMs = startMs)
            return
        }

        if (manga != null && !manga.chapters.isNullOrEmpty()) {
            lifecycleScope.launch {
                try {
                    val repo = mangaRepositoryFactory.create(manga.source)
                    android.util.Log.w("VideoPlayer", "repo=${repo!!::class.simpleName} chapters=${manga.chapters?.size} source=${manga.source.name}")
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
                            if (repo is AniyomiAnimeRepository) {
                                val videos = repo.getVideoListForChapter(currentChapter)
                                    .filter { it.videoUrl.isNotBlank() }
                                if (videos.isNotEmpty()) {
                                    availableVideos = videos
                                    updateQualityButtonVisibility()
                                    currentVideoSource = manga.source
                                    currentVideoIndex = videos.indexOfFirst { it.preferred }
                                        .takeIf { it >= 0 } ?: 0
                                    val selected = videos[currentVideoIndex]
                                    val mergedHeaders = mergeHeaders(repo.getRequestHeaders(), headersToMap(selected.headers))
                                    pendingExternalSubtitles = selected.subtitleTracks
                                    pendingExternalAudio = selected.audioTracks
                                    startMpvPlayback(
                                        selected.videoUrl,
                                        manga.source,
                                        mergedHeaders,
                                        startMs = startMs,
                                    )
                                    true
                                } else {
                                    null
                                }
                            } else {
                                null
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
                                    startMpvPlayback(
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
                                        prepareAndPlay(streamUrl, manga.source, streamHeaders, startMs = startMs)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
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
                startMpvPlayback(
                    url = sniffed.url,
                    source = source,
                    headers = mergeHeaders(headers, sniffed.headers),
                )
                return@launch
            }

            Log.w("VideoPlayer", "No playable media sniffed from web page, fallback to browser: $url")
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

    private fun startMpvPlayback(
        url: String,
        source: ParsersContentSource?,
        headers: Map<String, String>? = null,
        startMs: Long? = null,
    ) {
        hasRestoredProgress = false
        hasCurrentMediaLoaded = false
        currentMediaUrl = url
        currentVideoSource = source
        currentMediaHeaders = headers
        maybeLoadDanmaku()
        val mergedHeaders = headers.orEmpty()
        videoLocalCacheProxy.resetSessionStats("startMpvPlayback")
        val initialStartMs = startMs ?: resolveSavedPlaybackProgress(url)
        skipHistorySeekForCurrentMedia = initialStartMs != null
        effectivePlaybackConfig = playbackConfigOverride ?: VideoPlaybackPolicy.resolve(appSettings, devicePerformanceInfo)
        logEffectivePlaybackConfig()
        mpvPlayer?.setVideoOutput(resolveVideoRenderer(effectivePlaybackConfig.rendererMode))
        if (effectivePlaybackConfig.decoderMode == VideoDecoderMode.SOFTWARE) {
            mpvPlayer?.setHardwareDecodingMode("no")
        } else {
            mpvPlayer?.setHardwareDecodingMode("auto")
        }
        
        // Apply optimized streaming options for network stability
        mpvPlayer?.setStreamingOptions(appSettings.videoCacheSizeMb)
        
        applyPlaybackOptions()
        applyAspectRatio()
        val defaultSpeed = appSettings.videoDefaultSpeed
        appSettings.videoPlaybackSpeed = defaultSpeed
        mpvPlayer?.setRate(defaultSpeed.toDouble())

        Log.d("VideoPlayerActivity", "Loading media. URL: $url, Headers: ${mergedHeaders.keys}")
        val isHttpSource = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        val useProxy = shouldUseLocalProxy(url, isHttpSource, source)
        val dynamicCloudstreamPlaylistUrl = createCloudstreamPlaylistProxyUrl(
            url = url,
            headers = mergedHeaders,
            source = source,
        )
        val (playUrl, playHeaders) = if (dynamicCloudstreamPlaylistUrl != null) {
            Log.d("VideoPlayerActivity", "Using rewritten Cloudstream playlist proxy for URL: $url")
            dynamicCloudstreamPlaylistUrl to emptyMap<String, String>()
        } else if (useProxy) {
            runCatching {
                val proxyUrl = videoLocalCacheProxy.getProxyUrl(url, mergedHeaders, source)
                proxyUrl to emptyMap<String, String>()
            }.getOrElse {
                Log.w("VideoPlayerActivity", "Proxy cache unavailable, fallback to origin URL", it)
                url to mergedHeaders
            }
        } else {
            Log.d("VideoPlayerActivity", "Bypass local proxy for URL: $url")
            url to mergedHeaders
        }
        Log.d("VideoPlayerActivity", "Resolved playback URL: $playUrl, useProxy=$useProxy")
        
        val doLoad = {
            schedulePlaybackStartupTimeout()
            mpvPlayer?.load(playUrl, playHeaders, initialStartMs)
            mpvPlayer?.play()
        }
        
        val holder = mpvView.holder
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            doLoad()
        } else {
            Log.d("VideoPlayerActivity", "Surface not ready, waiting for surfaceCreated to load MPV")
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    holder.removeCallback(this)
                    Log.d("VideoPlayerActivity", "Surface ready, loading MPV now")
                    mpvView.post { doLoad() }
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
            })
        }
        updateTitleAndSubtitle()
        updatePlaybackMenu()
        if (!skipHistorySeekForCurrentMedia) {
            lifecycleScope.launch {
                restoreInitialSeekPercentFromHistory()
            }
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
            Log.d("VideoPlayerActivity", "m3u8 proxy cache disabled by feature flag")
            return false
        }
        return true
    }

    private fun createCloudstreamPlaylistProxyUrl(
        url: String,
        headers: Map<String, String>,
        source: ParsersContentSource?,
    ): String? {
        if (source !is CloudstreamSource) return null
        if (!url.contains("/config-", ignoreCase = true)) return null
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
            val targetUrl = request.queryParameters["target"].takeUnless { it.isNullOrBlank() } ?: url
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
            if (isCloudstreamPlaylistResponse(targetUrl, contentType)) {
                val playlist = body.string()
                upstreamResponse.close()
                val rewritten = rewriteCloudstreamPlaylistForProxy(
                    playlist = playlist,
                    baseUrl = targetUrl,
                    proxyBaseUrl = proxyBaseUrl,
                )
                Log.d(
                    "VideoPlayerActivity",
                    "Cloudstream playlist preview:\n${rewritten.lineSequence().take(8).joinToString("\n")}",
                )
                return@getDynamicProxyUrl VideoLocalCacheProxy.DynamicResponse(
                    statusCode = 200,
                    contentType = "application/vnd.apple.mpegurl; charset=utf-8",
                    headers = mapOf("Cache-Control" to "no-cache"),
                    body = rewritten.toByteArray(Charsets.UTF_8),
                )
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
        val key = request.pathSegments.lastOrNull().orEmpty()
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
            val field = videoLocalCacheProxy.javaClass.getDeclaredField("okHttpClient")
            field.isAccessible = true
            val okHttpClient = field.get(videoLocalCacheProxy) as okhttp3.OkHttpClient
            okHttpClient.newCall(request).execute()
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
        val rewritten = Uri.parse(proxyBaseUrl).buildUpon()
            .appendQueryParameter("target", normalizedTargetUrl)
            .build()
            .toString()
        if (rewritten != rawUrl) {
            Log.d(
                "VideoPlayerActivity",
                "Rewrote Cloudstream playlist URL from=$rawUrl to=$rewritten",
            )
        }
        return rewritten
    }

    private fun buildCloudstreamProxyHeaders(response: Response): Map<String, String> {
        return buildMap {
            response.header("Content-Length")?.let { put("Content-Length", it) }
            response.header("Accept-Ranges")?.let { put("Accept-Ranges", it) }
            response.header("Content-Range")?.let { put("Content-Range", it) }
            response.header("Cache-Control")?.let { put("Cache-Control", it) }
        }
    }

    private fun resolveVideoRenderer(rendererMode: VideoRendererMode): String {
        return when (rendererMode) {
            VideoRendererMode.AUTO -> {
                if (Build.VERSION.SDK_INT >= 34) "gpu-next" else "gpu"
            }
            VideoRendererMode.GPU -> "gpu"
            VideoRendererMode.GPU_NEXT -> "gpu-next"
            VideoRendererMode.MEDIACODEC_EMBED -> "mediacodec_embed"
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
                videoTitle = "",
                resolution = null,
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
            val pos = mpvPlayer?.positionMs ?: return
            if (pos < introEndMs) {
                hasSkippedIntro = true
                mpvPlayer?.seekTo(introEndMs)
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
        val showMarkerActions = !isLandscapeOrientation()
        val actions = buildList {
            add(
                PlayerOverflowAction(
                    title = getString(R.string.open_external_player),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_open_external,
                    onClick = ::openInExternalPlayer,
                ),
            )
            add(
                PlayerOverflowAction(
                    title = getString(R.string.cast_to_device),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_cast,
                    onClick = ::showDlnaDeviceSheet,
                ),
            )
            add(
                PlayerOverflowAction(
                    title = getString(R.string.video_picture_in_picture),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_picture_in_picture,
                    onClick = ::enterPictureInPicture,
                ),
            )
            add(
                PlayerOverflowAction(
                    title = getString(R.string.video_detail),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_info_outline,
                    onClick = ::openVideoDetails,
                ),
            )
            if (showMarkerActions) {
                add(
                    PlayerOverflowAction(
                        title = buildIntroMenuTitle(),
                        iconRes = org.skepsun.kototoro.R.drawable.ic_prev,
                        onClick = ::toggleIntroMarker,
                    ),
                )
                add(
                    PlayerOverflowAction(
                        title = buildOutroMenuTitle(),
                        iconRes = org.skepsun.kototoro.R.drawable.ic_next,
                        onClick = ::toggleOutroMarker,
                    ),
                )
            }
            add(
                PlayerOverflowAction(
                    title = getString(R.string.rotate_screen),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_screen_rotation,
                    onClick = { orientationHelper.isLandscape = !orientationHelper.isLandscape },
                ),
            )
            add(
                PlayerOverflowAction(
                    title = getString(R.string.video_aspect_ratio),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_aspect_ratio,
                    onClick = ::showAspectRatioDialog,
                ),
            )
            add(
                PlayerOverflowAction(
                    title = getString(R.string.save_manga_video),
                    iconRes = org.skepsun.kototoro.R.drawable.ic_download,
                    onClick = ::downloadCurrentChapter,
                ),
            )
        }
        actionDialogState = VideoActionDialogState(
            title = getString(R.string.options),
            items = actions.map { action ->
                VideoActionDialogItem(action.title, iconRes = action.iconRes, onClick = action.onClick)
            },
            anchorBounds = lastMoreAnchorBounds,
        )
    }

    private fun buildPlayerSettingsActions(): List<PlayerSettingsAction> {
        val enabledText = getString(R.string.enabled)
        val disabledText = getString(R.string.disabled)
        return listOf(
            PlayerSettingsAction(
                title = getString(R.string.video_reload),
                iconRes = org.skepsun.kototoro.R.drawable.ic_retry,
                onClick = ::reloadPlayback,
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_quality),
                subtitle = currentQualityLabel(),
                iconRes = org.skepsun.kototoro.R.drawable.ic_network_cellular,
                onClick = ::showQualityDialog,
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_super_resolution),
                subtitle = appSettings.videoSuperResolutionMode.name,
                iconRes = org.skepsun.kototoro.R.drawable.ic_auto_fix,
                onClick = ::showVideoSuperResolutionSheet,
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_screenshot),
                iconRes = org.skepsun.kototoro.R.drawable.ic_save,
                onClick = ::takeScreenshot,
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_playback_speed),
                subtitle = "%.2fx".format(appSettings.videoPlaybackSpeed),
                iconRes = org.skepsun.kototoro.R.drawable.ic_timer,
                onClick = ::showPlaybackSpeedDialog,
            ),
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
                title = getString(R.string.video_aspect_ratio),
                subtitle = currentAspectRatioLabel(),
                iconRes = org.skepsun.kototoro.R.drawable.ic_aspect_ratio,
                onClick = ::showAspectRatioDialog,
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
            PlayerSettingsAction(
                title = getString(R.string.video_subtitle_track),
                subtitle = currentSubtitleTrackLabel(),
                iconRes = org.skepsun.kototoro.R.drawable.ic_subtitles,
                onClick = ::showSubtitleTrackDialog,
            ),
            PlayerSettingsAction(
                title = getString(R.string.video_audio_track),
                subtitle = currentAudioTrackLabel(),
                iconRes = org.skepsun.kototoro.R.drawable.ic_audiotrack,
                onClick = ::showAudioTrackDialog,
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

    private fun buildIntroMenuTitle(): String {
        return if (introEndMs > 0) {
            getString(R.string.video_mark_intro) + ": " + formatTimeMs(introEndMs)
        } else {
            getString(R.string.video_mark_intro)
        }
    }

    private fun buildOutroMenuTitle(): String {
        return if (outroStartMs > 0) {
            getString(R.string.video_mark_outro) + ": " + formatTimeMs(outroStartMs)
        } else {
            getString(R.string.video_mark_outro)
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
            val pos = mpvPlayer?.positionMs ?: return
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
            val pos = mpvPlayer?.positionMs ?: return
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
            manga.chapters?.find { it.id == state.chapterId }?.title
                ?: fallbackUrl?.let { deriveEpisodeTitle(it) }
                ?: ""
        } else {
            fallbackUrl?.let { deriveEpisodeTitle(it) }
                ?: ""
        }
        
        return Pair(title, chapterName)
    }

    private fun updateTitleAndSubtitle() {
        syncComposeControlState()
    }

    /**
     * Load any external subtitle and audio tracks from the Aniyomi Video model.
     * Called after file is loaded so MPV can accept sub-add/audio-add commands.
     */
    private fun loadPendingExternalTracks() {
        val player = mpvPlayer ?: return
        val subs = pendingExternalSubtitles.toList()
        val audios = pendingExternalAudio.toList()
        pendingExternalSubtitles = emptyList()
        pendingExternalAudio = emptyList()

        if (subs.isEmpty() && audios.isEmpty()) {
            autoSelectTracksByLanguage()
            return
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (subs.isNotEmpty()) {
                android.util.Log.d("VideoPlayerActivity", "Loading ${subs.size} external subtitle tracks")
                for (track in subs) {
                    player.addSubtitleTrack(track.url, track.lang, track.lang)
                }
            }
            if (audios.isNotEmpty()) {
                android.util.Log.d("VideoPlayerActivity", "Loading ${audios.size} external audio tracks")
                for (track in audios) {
                    player.addAudioTrack(track.url, track.lang, track.lang)
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isDestroyed && !isFinishing) {
                    autoSelectTracksByLanguage()
                }
            }
        }
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

    /**
     * Poll MPV's sub-text property and update the subtitle overlay.
     * Called every 1s from the controller progress runnable while controls are visible.
     * This is a reliable fallback since property observation may not work
     * for string properties in some mpv-android-lib versions.
     */
    private fun pollSubtitleText() {
        val player = mpvPlayer ?: return
        val text = player.getPropertyString("sub-text")
        if (text != lastSubtitleText) {
            lastSubtitleText = text
            updateSubtitleOverlay(text)
        }
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

        mpvPlayer?.applySubtitleStyle(
            fontSizeSp = settings.videoSubtitleFontSize,
            isBold = settings.videoSubtitleBold,
            isItalic = settings.videoSubtitleItalic,
            textColor = settings.videoSubtitleTextColor,
            borderColor = settings.videoSubtitleBorderColor,
            borderSize = settings.videoSubtitleBorderSize,
            backgroundColor = settings.videoSubtitleBgColor,
            alignX = settings.videoSubtitleAlignX,
            position = settings.videoSubtitlePosition,
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
        val player = mpvPlayer ?: return
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

    private fun autoSelectSubtitleBySystemLanguage(subTracks: List<MpvPlayer.TrackInfo>) {
        val systemLang = java.util.Locale.getDefault().language
        val player = mpvPlayer ?: return
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
        val vo = mpvPlayer?.getPropertyString("vo")
        val voParams = mpvPlayer?.getPropertyString("video-out-params/vo")
        val hwdec = mpvPlayer?.getPropertyString("hwdec-current")
        val voCombined = listOfNotNull(vo, voParams).joinToString("|")
        val isMediacodecEmbed = voCombined.contains("mediacodec_embed", ignoreCase = true)
        android.util.Log.d("MpvPlayer", "SuperResolution check: vo=$vo voParams=$voParams hwdec=$hwdec")
        if (isMediacodecEmbed || !effectivePlaybackConfig.allowShaderPipeline) {
            android.util.Log.d("MpvPlayer", "SuperResolution disabled: vo=$voCombined hwdec=$hwdec")
            mpvPlayer?.applyShaderList(null)
            if (effectivePlaybackConfig.decoderMode == VideoDecoderMode.SOFTWARE) {
                mpvPlayer?.setHardwareDecodingMode("no")
            } else {
                mpvPlayer?.setHardwareDecodingMode("auto")
            }
            return
        }
        if (effectivePlaybackConfig.superResolutionMode == VideoSuperResolutionMode.OFF) {
            android.util.Log.d("MpvPlayer", "SuperResolution disabled: mode=OFF")
            mpvPlayer?.applyShaderList(null)
            if (effectivePlaybackConfig.decoderMode == VideoDecoderMode.SOFTWARE) {
                mpvPlayer?.setHardwareDecodingMode("no")
            } else {
                mpvPlayer?.setHardwareDecodingMode("auto")
            }
            return
        }
        if (effectivePlaybackConfig.decoderMode == VideoDecoderMode.SOFTWARE) {
            mpvPlayer?.setHardwareDecodingMode("no")
        } else {
            mpvPlayer?.setHardwareDecodingMode("mediacodec-copy")
        }
        val dir = MpvShaderManager.ensureShadersCopied(this)
        val shaderList = when (effectivePlaybackConfig.superResolutionMode) {
            VideoSuperResolutionMode.OFF -> emptyList()
            VideoSuperResolutionMode.QUALITY -> mapSubModeToPreset(
                resolveSubMode(VideoSuperResolutionMode.QUALITY, appSettings.videoSuperResolutionQualityShader)
            )
            VideoSuperResolutionMode.BALANCED -> mapSubModeToPreset(
                resolveSubMode(VideoSuperResolutionMode.BALANCED, appSettings.videoSuperResolutionBalancedShader)
            )
            VideoSuperResolutionMode.PERFORMANCE -> mapSubModeToPreset(
                resolveSubMode(VideoSuperResolutionMode.PERFORMANCE, appSettings.videoSuperResolutionPerformanceShader)
            )
            VideoSuperResolutionMode.ADVANCED -> {
                mapSubModeToPreset(appSettings.videoSuperResolutionShader)
            }
        }
        val shaderPaths = if (shaderList.isEmpty()) null else {
            MpvShaderManager.buildShaderPathList(dir, shaderList)
        }
        mpvPlayer?.applyShaderList(shaderPaths)
    }

    private fun logEffectivePlaybackConfig() {
        Log.i(
            "VideoPlayerActivity",
            "Playback policy: tier=${devicePerformanceInfo.tier} score=${devicePerformanceInfo.score} " +
                "ramMb=${devicePerformanceInfo.totalRamMb} cpu=${devicePerformanceInfo.cpuCores} " +
                "renderer=${effectivePlaybackConfig.rendererMode} decoder=${effectivePlaybackConfig.decoderMode} " +
                "superRes=${effectivePlaybackConfig.superResolutionMode} shaders=${effectivePlaybackConfig.allowShaderPipeline}"
        )
    }

    private fun schedulePlaybackStartupTimeout() {
        playerRoot.removeCallbacks(playbackStartupTimeoutRunnable)
        playerRoot.postDelayed(playbackStartupTimeoutRunnable, startupTimeoutMs)
    }

    private fun cancelPlaybackStartupTimeout() {
        playerRoot.removeCallbacks(playbackStartupTimeoutRunnable)
    }

    private fun handlePlaybackStartupTimeout() {
        handlePlaybackFallback("startup_timeout", null)
    }

    private fun handlePlaybackFallback(trigger: String, detail: String?) {
        // Disabled per user request
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

    private fun showVideoSettingsPanel(anchorBounds: IntRect = lastSettingsAnchorBounds) {
        if (anchorBounds != IntRect.Zero) {
            lastSettingsAnchorBounds = anchorBounds
        }
        actionDialogState = VideoActionDialogState(
            title = getString(R.string.options),
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
        )
    }

    private fun resolveSubMode(
        mode: VideoSuperResolutionMode,
        shader: VideoSuperResolutionShader,
    ): VideoSuperResolutionShader {
        return when (mode) {
            VideoSuperResolutionMode.OFF -> shader
            VideoSuperResolutionMode.QUALITY -> shader
            VideoSuperResolutionMode.BALANCED -> when (shader) {
                VideoSuperResolutionShader.MODE_AA -> VideoSuperResolutionShader.MODE_A
                VideoSuperResolutionShader.MODE_BB -> VideoSuperResolutionShader.MODE_B
                VideoSuperResolutionShader.MODE_CA -> VideoSuperResolutionShader.MODE_C
                else -> shader
            }
            VideoSuperResolutionMode.PERFORMANCE -> when (shader) {
                VideoSuperResolutionShader.MODE_A,
                VideoSuperResolutionShader.MODE_AA -> VideoSuperResolutionShader.MODE_B
                VideoSuperResolutionShader.MODE_B,
                VideoSuperResolutionShader.MODE_BB -> VideoSuperResolutionShader.MODE_C
                VideoSuperResolutionShader.MODE_C,
                VideoSuperResolutionShader.MODE_CA -> VideoSuperResolutionShader.MODE_C
                else -> shader
            }
            VideoSuperResolutionMode.ADVANCED -> shader
        }
    }

    private fun mapSubModeToPreset(shader: VideoSuperResolutionShader): List<String> {
        return when (shader) {
            VideoSuperResolutionShader.MODE_A -> MpvShaderManager.modeAPreset
            VideoSuperResolutionShader.MODE_B -> MpvShaderManager.modeBPreset
            VideoSuperResolutionShader.MODE_C -> MpvShaderManager.modeCPreset
            VideoSuperResolutionShader.MODE_AA -> MpvShaderManager.modeAPlusPreset
            VideoSuperResolutionShader.MODE_BB -> MpvShaderManager.modeBPlusPreset
            VideoSuperResolutionShader.MODE_CA -> MpvShaderManager.modeCAPlusPreset
            VideoSuperResolutionShader.CUSTOM -> appSettings.videoSuperResolutionCustomShaders.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun showSubtitleTrackDialog() {
        val player = mpvPlayer ?: return
        val tracks = player.getSubtitleTracks()
        if (tracks.isEmpty()) {
            showPlayerMessage(org.skepsun.kototoro.R.string.video_no_subtitle_tracks)
            return
        }
        val labels = arrayOf(getString(org.skepsun.kototoro.R.string.video_subtitle_off)) +
            tracks.map { it.displayName() }.toTypedArray()
        val selectedTrack = tracks.indexOfFirst { it.isSelected }
        val checked = if (selectedTrack >= 0) selectedTrack + 1 else 0

        showSelectionDialog(R.string.video_subtitle_track, labels.asList(), checked) { which ->
            if (which == 0) {
                player.setSubtitleTrack(null)
                userManualSubtitleSelection = ManualSubtitleSelection.Off
            } else {
                val track = tracks[which - 1]
                player.setSubtitleTrack(track.id)
                userManualSubtitleSelection = ManualSubtitleSelection.Track(
                    language = track.language,
                    title = track.title,
                )
            }
        }
    }

    private fun showAudioTrackDialog() {
        val player = mpvPlayer ?: return
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
        if (availableVideos.isEmpty()) {
            showPlayerMessage(org.skepsun.kototoro.R.string.operation_not_supported)
            return
        }
        val titles = availableVideos.mapIndexed { index, video ->
            video.qualityDisplayLabel(index)
        }.toTypedArray()
        val selected = currentVideoIndex.coerceIn(0, titles.lastIndex)
        showSelectionDialog(R.string.video_quality, titles.asList(), selected) { which ->
            if (which != currentVideoIndex) {
                switchVideoQuality(which)
            }
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

    private fun switchVideoQuality(which: Int) {
        val video = availableVideos[which]
        val resumeMs = mpvPlayer?.positionMs ?: 0L
        currentVideoIndex = which
        updateQualityButtonLabel()
        pendingExternalSubtitles = video.subtitleTracks
        pendingExternalAudio = video.audioTracks
        val repo = currentVideoSource?.let { src -> mangaRepositoryFactory.create(src) }
        val mergedHeaders = mergeHeaders(repo?.getRequestHeaders(), headersToMap(video.headers))
        startMpvPlayback(video.videoUrl, currentVideoSource, mergedHeaders, resumeMs)
    }

    private fun showVideoSuperResolutionSheet() {
        superResolutionDialogVisible = true
    }

    private fun buildSuperResolutionDialogState(): VideoSuperResolutionDialogState {
        val mode = appSettings.videoSuperResolutionMode
        val shader = when (mode) {
            VideoSuperResolutionMode.OFF -> appSettings.videoSuperResolutionShader
            VideoSuperResolutionMode.QUALITY -> appSettings.videoSuperResolutionQualityShader
            VideoSuperResolutionMode.BALANCED -> appSettings.videoSuperResolutionBalancedShader
            VideoSuperResolutionMode.PERFORMANCE -> appSettings.videoSuperResolutionPerformanceShader
            VideoSuperResolutionMode.ADVANCED -> appSettings.videoSuperResolutionShader
        }
        val selectedCustomShaders = appSettings.videoSuperResolutionCustomShaders
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val shaderDir = MpvShaderManager.ensureShadersCopied(this)
        val customShaders = shaderDir.listFiles { _, name -> name.endsWith(".glsl", ignoreCase = true) }
            .orEmpty()
            .map { file ->
                val descriptionName = "video_super_resolution_shader_desc_${file.nameWithoutExtension.lowercase()}"
                val descriptionRes = resources.getIdentifier(descriptionName, "string", packageName)
                VideoShaderOption(
                    fileName = file.name,
                    description = descriptionRes.takeIf { it != 0 }?.let(::getString),
                    selected = file.name in selectedCustomShaders,
                )
            }
            .sortedBy(VideoShaderOption::fileName)
        return VideoSuperResolutionDialogState(
            selectedMode = mode,
            selectedShader = shader,
            shaderLabels = VideoSuperResolutionShader.entries.associateWith(::superResolutionShaderLabel),
            customShaders = customShaders,
            anchorBounds = submenuAnchorBounds,
        )
    }

    private fun selectSuperResolutionMode(mode: VideoSuperResolutionMode) {
        appSettings.videoSuperResolutionMode = mode
        if (mode == VideoSuperResolutionMode.ADVANCED) {
            appSettings.videoSuperResolutionShader = VideoSuperResolutionShader.CUSTOM
        }
        applySuperResolutionFromSettings()
        superResolutionDialogVersion++
    }

    private fun selectSuperResolutionShader(shader: VideoSuperResolutionShader) {
        when (appSettings.videoSuperResolutionMode) {
            VideoSuperResolutionMode.OFF -> appSettings.videoSuperResolutionShader = shader
            VideoSuperResolutionMode.QUALITY -> appSettings.videoSuperResolutionQualityShader = shader
            VideoSuperResolutionMode.BALANCED -> appSettings.videoSuperResolutionBalancedShader = shader
            VideoSuperResolutionMode.PERFORMANCE -> appSettings.videoSuperResolutionPerformanceShader = shader
            VideoSuperResolutionMode.ADVANCED -> appSettings.videoSuperResolutionShader = shader
        }
        applySuperResolutionFromSettings()
        superResolutionDialogVersion++
    }

    private fun toggleCustomSuperResolutionShader(fileName: String, selected: Boolean) {
        val shaders = appSettings.videoSuperResolutionCustomShaders
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toMutableSet()
        if (selected) shaders += fileName else shaders -= fileName
        appSettings.videoSuperResolutionCustomShaders = shaders.joinToString(",")
        appSettings.videoSuperResolutionMode = VideoSuperResolutionMode.ADVANCED
        appSettings.videoSuperResolutionShader = VideoSuperResolutionShader.CUSTOM
        applySuperResolutionFromSettings()
        superResolutionDialogVersion++
    }

    private fun superResolutionShaderLabel(shader: VideoSuperResolutionShader): String = getString(
        when (shader) {
            VideoSuperResolutionShader.MODE_A -> R.string.video_super_resolution_mode_a
            VideoSuperResolutionShader.MODE_B -> R.string.video_super_resolution_mode_b
            VideoSuperResolutionShader.MODE_C -> R.string.video_super_resolution_mode_c
            VideoSuperResolutionShader.MODE_AA -> R.string.video_super_resolution_mode_aa
            VideoSuperResolutionShader.MODE_BB -> R.string.video_super_resolution_mode_bb
            VideoSuperResolutionShader.MODE_CA -> R.string.video_super_resolution_mode_ca
            VideoSuperResolutionShader.CUSTOM -> R.string.video_super_resolution_mode_custom
        },
    )

    private fun currentQualityLabel(): String? {
        if (availableVideos.isEmpty()) return null
        val index = currentVideoIndex.coerceIn(availableVideos.indices)
        return availableVideos[index].qualityDisplayLabel(index)
    }

    private fun currentAspectRatioLabel(): String {
        val labelRes = when (appSettings.videoAspectRatio) {
            1 -> R.string.video_aspect_ratio_fill
            2 -> R.string.video_aspect_ratio_16_9
            3 -> R.string.video_aspect_ratio_4_3
            4 -> R.string.video_aspect_ratio_stretch
            else -> R.string.video_aspect_ratio_fit
        }
        return getString(labelRes)
    }

    private fun currentSubtitleTrackLabel(): String {
        val player = mpvPlayer ?: return getString(R.string.video_subtitle_off)
        return player.getSubtitleTracks().find { it.isSelected }?.displayName()
            ?: getString(R.string.video_subtitle_off)
    }

    private fun currentAudioTrackLabel(): String? {
        val player = mpvPlayer ?: return null
        return player.getAudioTracks().find { it.isSelected }?.displayName()
    }

    private fun updatePlaybackSpeedButton() {
        syncComposeControlState()
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
        mpvPlayer?.pause()
        danmakuController.pause()
    }

    override fun onDestroy() {
        cancelPlaybackStartupTimeout()
        playerRoot.removeCallbacks(hideUiRunnable)
        playerRoot.removeCallbacks(progressUpdateRunnable)
        playerRoot.removeCallbacks(controllerProgressRunnable)
        playerRoot.removeCallbacks(progressSaveRunnable)
        stopLongSeek()
        // 兜底保存进度（本地与历史?
        savePlaybackProgress()
        saveHistoryProgressAsync()
        finishReadingSession()
        mpvPlayer?.release()
        mpvPlayer = null
        runCatching { mpvView.destroy() }
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
        mpvPlayer?.setRate(speed.toDouble())
    }

    fun applyPlaybackOptions() {
        val volume = if (appSettings.videoVolumeBoostEnabled) 130.0 else 100.0
        mpvPlayer?.setVolume(volume)
        val mpvCacheDir = getExternalFilesDir("mpv_cache") ?: File(filesDir, "mpv_cache")
        mpvPlayer?.applyCacheSettings(appSettings.videoCacheSizeMb, mpvCacheDir)
    }

    fun applyAspectRatio() {
        mpvPlayer?.setAspectRatio(appSettings.videoAspectRatio)
    }

    fun reloadPlayback() {
        val url = currentMediaUrl
        if (url.isNullOrBlank()) {
            showPlayerMessage(org.skepsun.kototoro.R.string.error_occurred)
            return
        }
        val resumeMs = mpvPlayer?.positionMs ?: 0L
        startMpvPlayback(url, currentVideoSource, currentMediaHeaders, resumeMs)
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
        val decoderSetting = when (appSettings.videoDecoderMode) {
            VideoDecoderMode.HARDWARE -> getString(org.skepsun.kototoro.R.string.video_info_hw_decoding)
            VideoDecoderMode.SOFTWARE -> getString(org.skepsun.kototoro.R.string.video_info_sw_decoding)
        }
        val rendererSetting = when (appSettings.videoRendererMode) {
            VideoRendererMode.AUTO -> getString(org.skepsun.kototoro.R.string.video_info_auto)
            VideoRendererMode.GPU -> "GPU"
            VideoRendererMode.GPU_NEXT -> "GPU Next"
            VideoRendererMode.MEDIACODEC_EMBED -> "MediaCodec Embed"
        }
        val hwdecCurrent = mpvPlayer?.getPropertyString("hwdec-current").orDash()
        val voCurrent = mpvPlayer?.getPropertyString("vo").orDash()
        val videoCodec = mpvPlayer?.getPropertyString("video-codec").orDash()
        val audioCodec = mpvPlayer?.getPropertyString("audio-codec-name").orDash()
        val videoWidth = mpvPlayer?.getPropertyString("video-params/w").orDash()
        val videoHeight = mpvPlayer?.getPropertyString("video-params/h").orDash()
        val fps = (
            mpvPlayer?.getPropertyString("estimated-vf-fps")
                ?: mpvPlayer?.getPropertyString("video-params/fps")
                ?: mpvPlayer?.getPropertyString("container-fps")
            ).orDash()
        val sourceName = currentVideoSource?.name.orDash()
        val proxyStats = videoLocalCacheProxy.getSessionStatsSnapshot()
        val diagnostics = playbackDiagnostics.snapshot()
        val effectiveRendererSetting = when (effectivePlaybackConfig.rendererMode) {
            VideoRendererMode.AUTO -> getString(org.skepsun.kototoro.R.string.video_info_auto)
            VideoRendererMode.GPU -> "GPU"
            VideoRendererMode.GPU_NEXT -> "GPU Next"
            VideoRendererMode.MEDIACODEC_EMBED -> "MediaCodec Embed"
        }
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
        val pipWidth = mpvPlayer?.getPropertyString("video-params/w")?.toIntOrNull()
        val pipHeight = mpvPlayer?.getPropertyString("video-params/h")?.toIntOrNull()
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
        val surfaceView = mpvView
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
                isPlaying = mpvPlayer?.isPlaying == true,
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
        val timeMs = mpvPlayer?.positionMs ?: return
        danmakuController.addLiveDanmaku(message, timeMs)
    }

    private fun savePlaybackProgress(
        completed: Boolean = false,
        propagateFailure: Boolean = false,
    ) {
        val currentUrl = currentMediaUrl
        val player = mpvPlayer
        val dur = mpvPlayer?.durationMs
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
        mpvPlayer?.seekTo(pos)
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
            android.util.Log.w("VideoPlayer", "Chapter mismatch: history has ${history.chapterId}, but playing ${currentChapterId}. Not restoring position.")
            // Don't restore position when chapter doesn't match
            return
        }
        
        val overall = history.percent
        if (overall !in 0f..1f) {
            android.util.Log.w("VideoPlayer", "Invalid history percent: $overall")
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
            android.util.Log.w("VideoPlayer", "Chapter not found for id=${history.chapterId}, using overall percent")
            pendingInitialSeekPercent = overall
            return
        }
        
        android.util.Log.d("VideoPlayer", "Found chapter: ${chapter.title} (id=${chapter.id})")
        
        val branchChapters = chapters.filter { it.branch == chapter.branch }
        val count = branchChapters.size
        if (count <= 0) {
            android.util.Log.w("VideoPlayer", "No chapters in branch '${chapter.branch}'")
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
        val pos = mpvPlayer?.positionMs ?: 0L
        if (pos > 0L) {
            pendingInitialSeekPercent = null
            return
        }
        val dur = mpvPlayer?.durationMs ?: 0L
        if (dur > 0) {
            mpvPlayer?.seekTo((p * dur).toLong())
            pendingInitialSeekPercent = null
        }
    }

    private fun saveHistoryProgressAsync(
        completed: Boolean = false,
        requireHistory: Boolean = false,
    ): Job? {
        val exo = mpvPlayer ?: return null
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
            android.util.Log.w("VideoPlayer", "ReaderState is null, cannot save accurate chapter progress")
        }

        fun computeSeriesPercent(m: org.skepsun.kototoro.parsers.model.Content, s: ReaderState, ep: Float): Float {
            val chapters = m.chapters ?: run {
                android.util.Log.w("VideoPlayer", "No chapters available for series percent calculation")
                return ep
            }
            val curr = chapters.find { it.id == s.chapterId } ?: run {
                android.util.Log.w("VideoPlayer", "Current chapter (id=${s.chapterId}) not found in chapters list")
                return ep
            }
            val branchChapters = chapters.filter { it.branch == curr.branch }
            val count = branchChapters.size
            if (count <= 0) {
                android.util.Log.w("VideoPlayer", "No chapters in branch '${curr.branch}'")
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
	                    android.util.Log.w("VideoPlayer", "Cannot load details from source for local file URL: ${mangaSeed.url}")
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
                android.util.Log.w("VideoPlayer", "Cannot save history: manga has no chapters")
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
                    android.util.Log.w("VideoPlayer", "Cannot create fallback ReaderState")
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
        mpvPlayer?.pause()
        danmakuController.pause()
    }

    private fun episodePercentToScroll(percent: Float): Int {
        return (percent.coerceIn(0f, 1f) * 10000).toInt()
    }

    private fun currentVideoRecordState(): ReaderState? {
        val state = readerState ?: return null
        val pos = mpvPlayer?.positionMs ?: 0L
        val dur = mpvPlayer?.durationMs ?: 0L
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
                        
                        startMpvPlayback(selected.videoUrl, manga.source, mergedHeaders)
                        updateTitleAndSubtitle()
                        resolved = true
                    }
                }
                
                // Fallback to getPages for non-Aniyomi sources
                if (!resolved) {
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
                        startMpvPlayback(selected.videoUrl, manga.source, mergedHeaders)
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
		val duration = mpvPlayer?.durationMs ?: 0L
		val position = mpvPlayer?.positionMs ?: 0L
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
        val positionMs = mpvPlayer?.positionMs ?: 0L
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
                mpvPlayer?.pause()
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

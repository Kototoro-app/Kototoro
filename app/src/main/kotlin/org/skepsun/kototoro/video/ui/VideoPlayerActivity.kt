package org.skepsun.kototoro.video.ui

import android.os.Bundle
import android.view.View
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.content.ContentValues
import android.os.Build
import android.view.Gravity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.DefaultTimeBar
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.view.ViewGroup
import android.view.SurfaceView
import android.app.PictureInPictureParams
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.util.Log
import androidx.core.content.ContextCompat
import org.skepsun.kototoro.core.util.ext.consumeAll
import org.skepsun.kototoro.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.parser.tvbox.TVBoxPlayback
import org.skepsun.kototoro.core.ui.BaseFullscreenActivity
import org.skepsun.kototoro.databinding.ActivityVideoPlayerBinding
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
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.reader.ui.ScreenOrientationHelper
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import androidx.core.view.updateLayoutParams
import com.google.android.material.color.MaterialColors
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.ColorUtils
import android.net.Uri
import java.net.URLDecoder
import android.media.AudioManager
import android.provider.Settings
import android.content.Context
import java.io.File
import java.net.URI
import kotlin.math.abs
import okhttp3.Headers
import org.skepsun.kototoro.core.util.ext.menuView
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
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.ui.SpaceSwitcherDelegate
import org.skepsun.kototoro.space.domain.awaitCompletion
import com.bytedance.danmaku.render.engine.DanmakuView
import eu.kanade.tachiyomi.animesource.model.Video
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import androidx.activity.viewModels

@AndroidEntryPoint
class VideoPlayerActivity : BaseFullscreenActivity<ActivityVideoPlayerBinding>(), ReaderNavigationCallback {
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
    private var originalToolbarHeightPx: Int = 0
    private var availableVideos: List<Video> = emptyList()
    private var currentVideoIndex: Int = 0
    private var currentVideoSource: ParsersContentSource? = null
    private var currentMediaHeaders: Map<String, String>? = null
    private var skipHistorySeekForCurrentMedia: Boolean = false
    private var pendingExternalSubtitles: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    private var pendingExternalAudio: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    private lateinit var mpvView: CustomMpvView
    private val danmakuController = VideoDanmakuController()
    private var danmakuLoadJob: Job? = null
    private var danmakuKey: String? = null

    @Inject
    lateinit var danmakuSourceManager: DanmakuSourceManager

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
    // 防止重复绑定 TimeBar listener（wireControllerButtons 会被多次调用?
    private val timeBarBoundControllerIds = mutableSetOf<Int>()
    private var currentMediaUrl: String? = null
    private var lastSubtitleTextFromPoll: String? = null
    private var subtitlePollCounter = 0
    // Track user's manual subtitle selection to restore after file reload
    private var userManualSubtitleSelection: ManualSubtitleSelection? = null
    private val mpvListener = object : MpvPlayer.Listener {
        override fun onDurationChanged(durationMs: Long) {
            if (!hasRestoredProgress && durationMs > 0) {
                runOnUiThread {
                    tryApplyInitialSeek()
                    hasRestoredProgress = true
                    viewBinding.root.removeCallbacks(progressSaveRunnable)
                    viewBinding.root.postDelayed(progressSaveRunnable, progressSaveIntervalMs)
                    // Try to skip intro after initial seek is applied
                    viewBinding.root.postDelayed({ trySkipIntro() }, 500)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                updatePlaybackMenu()
                updatePlayPauseButton()
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
                        com.google.android.material.snackbar.Snackbar.make(
                            viewBinding.root, 
                            "Auto-skipping ad and loading video...", 
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).show()
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
                    Snackbar.make(viewBinding.root, R.string.video_skipping_outro, Snackbar.LENGTH_SHORT).show()
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
            updateToolbarProgress()
            viewBinding.root.postDelayed(this, progressUpdateIntervalMs.toLong())
        }
    }
    // 底部控制条（PlayerControlView）进度与已播放时长的定时更新
    private val controllerProgressIntervalMs = 1000
    private var lastSubtitleText: String? = null
    private val controllerProgressRunnable = object : Runnable {
        override fun run() {
            updateControllerProgress()
            pollSubtitleText()
            viewBinding.root.postDelayed(this, controllerProgressIntervalMs.toLong())
        }
    }
    // 定期保存播放进度（每5秒）
    private val progressSaveIntervalMs = 5000L
	private val progressSaveRunnable = object : Runnable {
		override fun run() {
			savePlaybackProgress()
			viewBinding.root.postDelayed(this, progressSaveIntervalMs)
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
                    overlaySeekLeft.text = getString(R.string.video_rewind_time, sec.toString())
                } else {
                    overlaySeekRight.text = getString(R.string.video_fast_forward_time, sec.toString())
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
    private lateinit var overlaySeekLeft: TextView
    private lateinit var overlaySeekRight: TextView
    private lateinit var overlayPlayPause: TextView
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideLeftRunnable = Runnable { overlaySeekLeft.visibility = View.GONE }
    private val hideRightRunnable = Runnable { overlaySeekRight.visibility = View.GONE }
    private val hideCenterRunnable = Runnable { overlayPlayPause.visibility = View.GONE }
    private fun showOverlayLeft(text: String, durationMs: Long? = 1200) {
        overlaySeekLeft.text = text
        overlaySeekLeft.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideLeftRunnable)
        durationMs?.let { overlayHandler.postDelayed(hideLeftRunnable, it) }
    }
    private fun showOverlayRight(text: String, durationMs: Long? = 1200) {
        overlaySeekRight.text = text
        overlaySeekRight.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideRightRunnable)
        durationMs?.let { overlayHandler.postDelayed(hideRightRunnable, it) }
    }
    private fun showPlayPauseOverlay(text: String, durationMs: Long = 800) {
        overlayPlayPause.text = text
        overlayPlayPause.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideCenterRunnable)
        overlayHandler.postDelayed(hideCenterRunnable, durationMs)
    }
    private fun showLongSeekOverlay(direction: Int) {
        overlayHandler.removeCallbacks(hideLeftRunnable)
        overlayHandler.removeCallbacks(hideRightRunnable)
        if (direction < 0) {
            overlaySeekRight.visibility = View.GONE
            overlaySeekLeft.text = getString(R.string.video_rewind_time, "0")
            overlaySeekLeft.visibility = View.VISIBLE
        } else if (direction > 0) {
            overlaySeekLeft.visibility = View.GONE
            overlaySeekRight.text = getString(R.string.video_fast_forward_time, "0")
            overlaySeekRight.visibility = View.VISIBLE
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

    private fun allControllers(): List<PlayerControlView> = listOfNotNull(
        findViewById(org.skepsun.kototoro.R.id.controller_land),
        findViewById(org.skepsun.kototoro.R.id.controller_portrait),
    )

    private fun currentController(): PlayerControlView? {
        val controllerId = if (isLandscapeOrientation()) {
            org.skepsun.kototoro.R.id.controller_land
        } else {
            org.skepsun.kototoro.R.id.controller_portrait
        }
        return findViewById(controllerId)
    }

    private fun bindDanmakuOverlay() {
        val danmakuView = findViewById<DanmakuView>(
            org.skepsun.kototoro.R.id.danmaku_view
        ) ?: return
        danmakuController.attach(danmakuView)
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
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_occurred)
                .setMessage(R.string.video_player_native_init_failed)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finish()
                }
                .setOnDismissListener {
                    if (!isFinishing) finish()
                }
                .show()
        }.isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePerformanceInfo = DevicePerformanceClassifier.classify(this)
        effectivePlaybackConfig = VideoPlaybackPolicy.resolve(appSettings, devicePerformanceInfo)
        setContentView(ActivityVideoPlayerBinding.inflate(layoutInflater))
        // 将布局中的 MaterialToolbar 设为 SupportActionBar，以便正确显示标?副标题与导航按钮
        setSupportActionBar(viewBinding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        // 确保标题靠左显示，避免被右侧动作按钮挤占
        viewBinding.toolbar.setTitleCentered(false)
        viewBinding.toolbarProgress.bringToFront()
        // Ensure the entire toolbar container is on top
        viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.toolbar_container)?.bringToFront()
        applySubtitleOverlayStyle()
        applyPlaybackBackground()
        mpvView = findViewById(org.skepsun.kototoro.R.id.player_view)
        if (!initializeMpvRuntime()) {
            return
        }
        bindDanmakuOverlay()
        danmakuController.setPlaybackPositionProvider(
            positionProvider = { mpvPlayer?.positionMs ?: 0L },
            playingProvider = { mpvPlayer?.isPlaying == true },
        )
        applyDanmakuSettings()

        // 记录初始工具栏高度，用于按方向动态调整高?
        originalToolbarHeightPx = viewBinding.toolbar.layoutParams.height

        // 读取传入 ReaderState（可能来自阅读器路由，用于历史保存与初始定位）
        readerState = intent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)

        // Apply default orientation: portrait when foldable unfolded in portrait; else landscape
        observeFoldableStateForOrientation()

        spaceSwitcherDelegate.bind(
            activity = this,
            snackbarAnchor = viewBinding.root,
            origin = SpaceSwitchOrigin.VIDEO_PLAYER,
            availabilityProvider = {
                if (isScreenLocked) SpaceSwitchAvailability.UNAVAILABLE else SpaceSwitchAvailability.SAVE_AND_SWITCH
            },
            progressFlusher = SpaceProgressFlusher { flushForSpaceSwitch() },
        )

        // 设置菜单点击监听并复用给两个 Toolbar
        val onMenuItemClick = androidx.appcompat.widget.Toolbar.OnMenuItemClickListener { item ->
            if (spaceSwitcherDelegate.onMenuItemSelected(item)) {
                return@OnMenuItemClickListener true
            }
            when (item.itemId) {
                org.skepsun.kototoro.R.id.action_subtitle_track -> {
                    showSubtitleTrackDialog()
                    true
                }
                org.skepsun.kototoro.R.id.action_external_player -> {
                    openInExternalPlayer()
                    true
                }
                org.skepsun.kototoro.R.id.action_cast -> {
                    showDlnaDeviceSheet()
                    true
                }
                org.skepsun.kototoro.R.id.action_quality -> {
                    showQualityDialog()
                    true
                }
                org.skepsun.kototoro.R.id.action_pip -> {
                    enterPictureInPicture()
                    true
                }
                org.skepsun.kototoro.R.id.action_info -> {
                    openVideoDetails()
                    true
                }
                org.skepsun.kototoro.R.id.action_settings -> {
                    showVideoSettingsPanel()
                    true
                }
                org.skepsun.kototoro.R.id.action_more -> {
                    showOverflowMenu()
                    true
                }
                else -> false
            }
        }
        viewBinding.toolbar.setOnMenuItemClickListener(onMenuItemClick)
        val secondaryToolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(org.skepsun.kototoro.R.id.toolbar_secondary)
        secondaryToolbar?.setOnMenuItemClickListener(onMenuItemClick)

        rebuildToolbarMenuForOrientation()
        adjustToolbarForOrientation()
        rearrangeBottomToolbarForOrientation()

        lifecycleScope.launch {
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
                        viewBinding.root.rotation = 180f
                        kotlinx.coroutines.delay(2000)
                        viewBinding.root.rotation = 0f
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
        updateStatusBarByToolbar()
		applyControlsAlpha()

        // 绑定手势提示浮层视图
        overlaySeekLeft = findViewById(org.skepsun.kototoro.R.id.overlay_seek_left)
        overlaySeekRight = findViewById(org.skepsun.kototoro.R.id.overlay_seek_right)
        overlayPlayPause = findViewById(org.skepsun.kototoro.R.id.overlay_play_pause)

        // 初始化音量与亮度上下?
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initCurrentBrightness()

        // Hook player view gestures: 双击播放/暂停；单击显隐UI；长按左右持续快?快退
        findViewById<View>(org.skepsun.kototoro.R.id.player_view)?.let { pv ->
            pv.isClickable = true

            // State variables for gestures
            var isHorizontalScrubbing = false
            var isLongPressSpeeding = false
            var initialScrubPositionStart = 0L
            var initialTouchX = 0f
            var lastScrubPosition = 0L

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

        // 兜底点击区域：当控制器隐藏时，任何空白处点击也可唤回 UI
        viewBinding.root.isClickable = true
        viewBinding.root.setOnClickListener { setUiIsVisible(true) }

        // 同步系统导航栏颜色为底栏背景色，实现与小白条区域的视觉合?
        runCatching {
            val navColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = navColor
        }

        // Wire controller buttons: pages and settings
        wireControllerButtons()

        // Initialize screen lock overlay and unlock button
        initLockOverlay()

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
    
    private fun wireControllerButtons() {
        val currentContent = currentMangaContent()
        allControllers().forEach { ctl ->
            ctl.bringToFront()

            // 进度条可拖拽/点击快进快退：显式监听用?scrub，避免定时刷新覆盖拖动状?
            if (timeBarBoundControllerIds.add(ctl.id)) {
                ctl.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.let { timeBar ->
                    timeBar.isClickable = true
                    timeBar.isFocusable = true
                    timeBar.isFocusableInTouchMode = true
                    // 避免父级的点?手势拦截 TimeBar 的拖动事?
                    timeBar.setOnTouchListener { v, ev ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    val controlView = ctl
                    timeBar.addListener(object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                            isUserScrubbing = true
                        }

                        override fun onScrubMove(timeBar: TimeBar, position: Long) {
                            // 拖动时同步显示当前拖动位置，提升反馈一致?
                            val showHours = (mpvPlayer?.durationMs ?: 0L) >= 3600_000L
                            controlView.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)?.text =
                                formatTimeMs(position, forceHours = showHours)
                        }

                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                            isUserScrubbing = false
                            if (!canceled) {
                                val p = mpvPlayer
                                if (p != null && p.durationMs > 0) {
                                    p.seekTo(position)
                                }
                            }
                        }
                    })
                }
            }

            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_pages_thumbs)?.let { btn ->
                btn.isVisible = currentContent != null
                btn.setOnClickListener {
                    showChapterSelectionPanel(btn)
                }
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_quality)?.setOnClickListener {
                showQualityDialog()
            }
            ctl.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.apply {
                isVisible = appSettings.videoDoubleTapSeekEnabled
                setOnClickListener {
                    mpvPlayer?.let { p ->
                        val pos = (p.positionMs - appSettings.videoSeekBackwardMs).coerceAtLeast(0)
                        p.seekTo(pos)
                    }
                }
            }
            ctl.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.setOnClickListener {
                mpvPlayer?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                    updatePlaybackMenu()
                }
            }
            ctl.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.apply {
                isEnabled = true
                isClickable = true
                alpha = 1f
            }
            ctl.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.apply {
                isVisible = appSettings.videoDoubleTapSeekEnabled
                setOnClickListener {
                    mpvPlayer?.let { p ->
                        val pos = (p.positionMs + appSettings.videoSeekForwardMs).coerceAtMost(p.durationMs.coerceAtLeast(0))
                        p.seekTo(pos)
                    }
                }
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_prev_chapter)?.setOnClickListener {
                navigateChapter(-1)
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_next_chapter)?.setOnClickListener {
                navigateChapter(1)
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_mark_intro)?.setOnClickListener {
                toggleIntroMarker()
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_mark_outro)?.setOnClickListener {
                toggleOutroMarker()
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_playback_speed)?.setOnClickListener {
                showPlaybackSpeedDialog()
            }

            // Screen lock button
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_screen_lock)?.setOnClickListener {
                enterScreenLock()
            }
        }
        updateChapterNavButtons()
        updateScreenLockButtonState()
    }

    private fun updateQualityButtonVisibility() {
        allControllers().forEach { ctl ->
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_quality)?.isVisible = availableVideos.isNotEmpty()
        }
        updateQualityButtonLabel()
    }

    private fun updateQualityButtonLabel() {
        val label = buildQualityButtonLabel()
        allControllers().forEach { ctl ->
            ctl.findViewById<com.google.android.material.button.MaterialButton>(
                org.skepsun.kototoro.R.id.button_quality,
            )?.apply {
                text = label
                contentDescription = getString(org.skepsun.kototoro.R.string.video_quality) + ": " + label
            }
        }
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
                Snackbar.make(
                    viewBinding.root,
                    org.skepsun.kototoro.R.string.error_occurred,
                    Snackbar.LENGTH_LONG,
                ).show()
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
                            Snackbar.make(
                                viewBinding.root,
                                org.skepsun.kototoro.R.string.error_occurred,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        android.util.Log.e("VideoPlayer", "Current chapter not found")
                        Snackbar.make(
                            viewBinding.root,
                            org.skepsun.kototoro.R.string.error_occurred,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VideoPlayer", "Failed to load video", e)
                    if (resolvePlaybackException(e, normalizedUrl, source, headers, startMs)) {
                        return@launch
                    }
                    Snackbar.make(
                        viewBinding.root,
                        org.skepsun.kototoro.R.string.error_occurred,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            android.util.Log.e("VideoPlayer", "Cannot resolve non-direct URL without manga info")
            Snackbar.make(
                viewBinding.root,
                org.skepsun.kototoro.R.string.error_occurred,
                Snackbar.LENGTH_LONG
            ).show()
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
        applyGradientAlpha()
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
        wireControllerButtons()
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
        val alpha = appSettings.videoControlsAlpha.coerceIn(0f, 1f)
        val colored = android.graphics.Color.argb((alpha * 255f).toInt(), 0, 0, 0)
        val useGradient = appSettings.videoGradientAlpha.coerceIn(0f, 1f) > 0f

        // 仅调整背景透明度，避免工具栏文本被整体 alpha 变淡
        viewBinding.toolbar.alpha = 1f
        val bgColor = if (useGradient) android.graphics.Color.TRANSPARENT else colored
        viewBinding.toolbar.setBackgroundColor(bgColor)
        viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.toolbar_secondary)?.setBackgroundColor(bgColor)
        val titleColor = MaterialColors.getColor(viewBinding.toolbar, com.google.android.material.R.attr.colorOnSurface)
        val subtitleColor = MaterialColors.getColor(viewBinding.toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant)
        viewBinding.toolbar.setTitleTextColor(titleColor)
        viewBinding.toolbar.setSubtitleTextColor(subtitleColor)

        allControllers().forEach { ctl ->
            ctl.alpha = 1f
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.toolbar_docked)
                ?.setBackgroundColor(if (useGradient) android.graphics.Color.TRANSPARENT else colored)
        }
        viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.status_bar_scrim)?.apply {
            this.alpha = 1f
            setBackgroundColor(if (useGradient) android.graphics.Color.TRANSPARENT else colored)
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = if (useGradient) android.graphics.Color.TRANSPARENT else colored
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (useGradient) android.graphics.Color.TRANSPARENT else colored
        val isLight = ColorUtils.calculateLuminance(colored) > 0.5
        WindowInsetsControllerCompat(window, viewBinding.root).setAppearanceLightStatusBars(isLight)
        applyGradientAlpha()
    }

    private fun applyGradientAlpha() {
        val alpha = appSettings.videoGradientAlpha.coerceIn(0f, 1f)
        val topGradient = viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.top_gradient)
        val bottomGradient = viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.bottom_gradient)
        topGradient?.alpha = alpha
        bottomGradient?.alpha = alpha
        val visible = alpha > 0f && isUiVisible
        topGradient?.isVisible = visible
        bottomGradient?.isVisible = visible
    }

    private fun setUiIsVisible(visible: Boolean) {
        applyPlayerUiState(if (visible) PlayerUiState.ControlsVisible else PlayerUiState.Hidden)
    }

    private fun applyPlayerUiState(state: PlayerUiState) {
        playerUiState = state
        isUiVisible = state == PlayerUiState.ControlsVisible

        val controlsVisible = state == PlayerUiState.ControlsVisible
        val topBar = viewBinding.toolbar
        val secondaryToolbar =
            viewBinding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(
                org.skepsun.kototoro.R.id.toolbar_secondary,
            )
        val statusBarScrim = viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.status_bar_scrim)
        val topGradient = viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.top_gradient)
        val bottomGradient = viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.bottom_gradient)
        val controller = currentController()
        val lockOverlay = findViewById<View>(org.skepsun.kototoro.R.id.lock_overlay)
        val unlockButton = findViewById<View>(org.skepsun.kototoro.R.id.button_screen_unlock)
        val subtitleOverlay = findViewById<View>(org.skepsun.kototoro.R.id.subtitle_overlay)

        topBar.isVisible = controlsVisible
        if (!controlsVisible) {
            secondaryToolbar?.isVisible = false
        }
        statusBarScrim?.isVisible = controlsVisible
        topGradient?.isVisible = controlsVisible
        bottomGradient?.isVisible = controlsVisible

        if (controlsVisible) {
            topGradient?.bringToFront()
            bottomGradient?.bringToFront()
            statusBarScrim?.bringToFront()
            viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.toolbar_container)?.bringToFront()
            controller?.bringToFront()
            findViewById<View>(org.skepsun.kototoro.R.id.seek_feedback_layout)?.bringToFront()
            findViewById<View>(org.skepsun.kototoro.R.id.overlay_seek_left)?.bringToFront()
            findViewById<View>(org.skepsun.kototoro.R.id.overlay_seek_right)?.bringToFront()
            findViewById<View>(org.skepsun.kototoro.R.id.overlay_play_pause)?.bringToFront()
        }

        systemUiController.setSystemUiVisible(false)
        updateStatusBarByToolbar()

        if (controlsVisible) {
            rebuildToolbarMenuForOrientation()
            adjustToolbarForOrientation()
            rearrangeBottomToolbarForOrientation()
            updatePlayPauseButton()
        } else {
            viewBinding.toolbar.menu.clear()
            secondaryToolbar?.menu?.clear()
        }

        allControllers().forEach { ctl ->
            if (controlsVisible && ctl == controller) {
                ctl.visibility = View.VISIBLE
                ctl.alpha = 1f
                applyControllerTint(ctl)
            } else {
                ctl.visibility = View.GONE
            }
        }

        if (state == PlayerUiState.Locked) {
            lockOverlay?.isVisible = true
            lockOverlay?.bringToFront()
            subtitleOverlay?.bringToFront()
        } else {
            lockOverlay?.isVisible = false
            unlockButton?.isVisible = false
            unlockButton?.alpha = 1f
        }

        viewBinding.root.requestApplyInsets()
        viewBinding.root.removeCallbacks(hideUiRunnable)
        viewBinding.root.removeCallbacks(progressUpdateRunnable)
        viewBinding.root.removeCallbacks(hideLockUiRunnable)
        viewBinding.root.removeCallbacks(controllerProgressRunnable)

        if (controlsVisible) {
            if (!isHorizontalScrubbing && !isUserScrubbing && verticalAdjustMode == 0) {
                viewBinding.root.postDelayed(hideUiRunnable, autoHideDelayMs.toLong())
            }
            updateToolbarProgress()
            viewBinding.root.postDelayed(progressUpdateRunnable, progressUpdateIntervalMs.toLong())
            updateControllerProgress()
            viewBinding.root.postDelayed(controllerProgressRunnable, controllerProgressIntervalMs.toLong())
        } else {
            viewBinding.toolbarProgress.isVisible = false
        }
    }

    // ==================== Screen Lock ====================

    private val lockAutoHideDelayMs = 3000L
    private val hideLockUiRunnable = Runnable {
        findViewById<View>(org.skepsun.kototoro.R.id.button_screen_unlock)?.let {
            it.animate().alpha(0f).setDuration(200).withEndAction { it.isVisible = false }.start()
        }
    }

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
        viewBinding.root.removeCallbacks(hideLockUiRunnable)
        findViewById<View>(org.skepsun.kototoro.R.id.lock_overlay)?.isVisible = false
        val unlockBtn = findViewById<View>(org.skepsun.kototoro.R.id.button_screen_unlock)
        unlockBtn?.isVisible = false
        unlockBtn?.alpha = 1f
        applyPlayerUiState(PlayerUiState.ControlsVisible)
    }

    private fun showLockedUi() {
        viewBinding.root.removeCallbacks(hideLockUiRunnable)
        // Show unlock button with fade-in
        val unlockBtn = findViewById<View>(org.skepsun.kototoro.R.id.button_screen_unlock)
        unlockBtn?.alpha = 0f
        unlockBtn?.isVisible = true
        unlockBtn?.animate()?.alpha(1f)?.setDuration(200)?.start()
        unlockBtn?.bringToFront()
        val lockOverlay = findViewById<View>(org.skepsun.kototoro.R.id.lock_overlay)
        lockOverlay?.bringToFront()
        unlockBtn?.bringToFront()
        viewBinding.root.postDelayed(hideLockUiRunnable, lockAutoHideDelayMs)
    }

    private fun initLockOverlay() {
        val lockOverlay = findViewById<View>(org.skepsun.kototoro.R.id.lock_overlay)
        val unlockBtn = findViewById<android.widget.ImageButton>(org.skepsun.kototoro.R.id.button_screen_unlock)
        lockOverlay?.setOnClickListener {
            if (isScreenLocked) {
                showLockedUi()
            }
        }
        unlockBtn?.setOnClickListener {
            exitScreenLock()
        }
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
                Snackbar.make(viewBinding.root, R.string.video_skipping_intro, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateIntroOutroButtonState() {
        allControllers().forEach { ctl ->
            val introButton = ctl.findViewById<com.google.android.material.button.MaterialButton>(
                org.skepsun.kototoro.R.id.button_mark_intro,
            )
            val outroButton = ctl.findViewById<com.google.android.material.button.MaterialButton>(
                org.skepsun.kototoro.R.id.button_mark_outro,
            )
            introButton?.isVisible = isLandscapeOrientation()
            outroButton?.isVisible = isLandscapeOrientation()
            introButton?.text = if (introEndMs > 0) formatTimeMs(introEndMs) else getString(R.string.video_mark_intro)
            outroButton?.text = if (outroStartMs > 0) formatTimeMs(outroStartMs) else getString(R.string.video_mark_outro)
        }
    }

    private fun updateScreenLockButtonState() {
        allControllers().forEach { ctl ->
            val lockButton = ctl.findViewById<com.google.android.material.button.MaterialButton>(
                org.skepsun.kototoro.R.id.button_screen_lock,
            ) ?: return@forEach
            lockButton.setIconResource(
                if (isScreenLocked) org.skepsun.kototoro.R.drawable.ic_lock_open
                else org.skepsun.kototoro.R.drawable.ic_lock,
            )
            lockButton.contentDescription = getString(
                if (isScreenLocked) org.skepsun.kototoro.R.string.video_screen_unlock
                else org.skepsun.kototoro.R.string.video_screen_lock,
            )
        }
    }

    private fun showOverflowMenu() {
        val toolbar = if (isLandscapeOrientation()) {
            viewBinding.toolbar
        } else {
            findViewById<com.google.android.material.appbar.MaterialToolbar>(org.skepsun.kototoro.R.id.toolbar_secondary)
                ?: viewBinding.toolbar
        }
        val anchor = toolbar.menuView?.findViewById<View>(org.skepsun.kototoro.R.id.action_more) ?: toolbar
        val showMarkerActions = !isLandscapeOrientation()
        val actions = buildList {
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
        showPlayerOverflowPopup(anchor, actions)
    }

    private fun showPlayerOverflowPopup(anchor: View, actions: List<PlayerOverflowAction>) {
        if (actions.isEmpty()) return

        lateinit var popup: PopupWindow
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@VideoPlayerActivity,
                org.skepsun.kototoro.R.drawable.bg_video_player_popup_menu,
            )
            actions.forEach { action ->
                addView(createPlayerOverflowRow(action) { popup.dismiss() })
            }
        }
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(10).toFloat()
            }
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val xOffset = anchor.width - content.measuredWidth
        popup.showAsDropDown(anchor, xOffset, dp(8), Gravity.NO_GRAVITY)
    }

    private fun createPlayerOverflowRow(
        action: PlayerOverflowAction,
        dismissPopup: () -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(220)
            minimumHeight = dp(48)
            setPadding(dp(14), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissPopup()
                action.onClick()
            }
            addView(
                ImageView(context).apply {
                    setImageResource(action.iconRes)
                    setColorFilter(Color.WHITE)
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginEnd = dp(14)
                },
            )
            addView(
                TextView(context).apply {
                    text = action.title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 1
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
        }
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

    private fun showPlayerSettingsPopup(anchor: View, actions: List<PlayerSettingsAction>) {
        if (actions.isEmpty()) return

        lateinit var popup: PopupWindow
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@VideoPlayerActivity,
                org.skepsun.kototoro.R.drawable.bg_video_player_popup_menu,
            )
            addView(createPlayerPanelHeader(getString(R.string.options)))
            actions.forEach { action ->
                addView(createPlayerSettingsRow(action) { popup.dismiss() })
            }
        }
        val content = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(panel)
        }
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(12).toFloat()
            }
        }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.72f).roundToInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        popup.height = content.measuredHeight.coerceAtMost(maxHeight)
        val xOffset = anchor.width - content.measuredWidth
        popup.showAsDropDown(anchor, xOffset, dp(8), Gravity.NO_GRAVITY)
    }

    private fun createPlayerPanelHeader(title: String): View {
        return TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
    }

    private fun createPlayerSettingsRow(
        action: PlayerSettingsAction,
        dismissPopup: () -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(336)
            minimumHeight = dp(54)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissPopup()
                action.onClick()
            }
            addView(
                ImageView(context).apply {
                    setImageResource(action.iconRes)
                    setColorFilter(Color.WHITE)
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginEnd = dp(14)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = action.title
                            setTextColor(Color.WHITE)
                            textSize = 15f
                            maxLines = 1
                            includeFontPadding = false
                        },
                    )
                    action.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                        addView(
                            TextView(context).apply {
                                text = subtitle
                                setTextColor(0xB3FFFFFF.toInt())
                                textSize = 12f
                                maxLines = 1
                                includeFontPadding = false
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(4)
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            action.isChecked?.let { checked ->
                addView(
                    ImageView(context).apply {
                        setImageResource(org.skepsun.kototoro.R.drawable.ic_check)
                        setColorFilter(if (checked) Color.WHITE else 0x40FFFFFF)
                        alpha = if (checked) 1f else 0.35f
                    },
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                        marginStart = dp(12)
                    },
                )
            }
        }
    }

    private fun showChapterSelectionPanel(anchor: View) {
        val chapters = playerChapterList()
        if (chapters.isEmpty()) return

        lateinit var popup: PopupWindow
        val currentId = readerState?.chapterId
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@VideoPlayerActivity,
                org.skepsun.kototoro.R.drawable.bg_video_player_popup_menu,
            )
            addView(createPlayerPanelHeader(getString(R.string.chapters)))
            chapters.forEachIndexed { index, chapter ->
                addView(
                    createPlayerChapterRow(
                        index = index,
                        chapter = chapter,
                        isCurrent = chapter.id == currentId,
                    ) {
                        popup.dismiss()
                        onChapterSelected(chapter)
                    },
                )
            }
        }
        val content = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(panel)
        }
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(12).toFloat()
            }
        }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.62f).roundToInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        popup.height = content.measuredHeight.coerceAtMost(maxHeight)
        val xOffset = ((anchor.width - content.measuredWidth) / 2).coerceAtMost(0)
        popup.showAsDropDown(anchor, xOffset, -anchor.height - popup.height - dp(8), Gravity.NO_GRAVITY)
    }

    private fun createPlayerChapterRow(
        index: Int,
        chapter: ContentChapter,
        isCurrent: Boolean,
        onClick: () -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(336)
            minimumHeight = dp(54)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            isFocusable = true
            alpha = if (isCurrent) 1f else 0.9f
            setOnClickListener { onClick() }
            addView(
                TextView(context).apply {
                    text = (index + 1).toString()
                    setTextColor(0xB3FFFFFF.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(12)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = chapter.title?.takeIf { it.isNotBlank() }
                                ?: chapter.url
                            setTextColor(Color.WHITE)
                            textSize = 15f
                            maxLines = 1
                            includeFontPadding = false
                        },
                    )
                    chapter.branch?.takeIf { it.isNotBlank() }?.let { branch ->
                        addView(
                            TextView(context).apply {
                                text = branch
                                setTextColor(0x99FFFFFF.toInt())
                                textSize = 12f
                                maxLines = 1
                                includeFontPadding = false
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(4)
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (isCurrent) {
                addView(
                    ImageView(context).apply {
                        setImageResource(org.skepsun.kototoro.R.drawable.ic_check)
                        setColorFilter(Color.WHITE)
                    },
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                        marginStart = dp(12)
                    },
                )
            }
        }
    }

    private fun playerChapterList(): List<ContentChapter> {
        return chaptersViewModel.chapters.value.map { it.chapter }.ifEmpty {
            currentMangaContent()?.chapters.orEmpty()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
            Snackbar.make(viewBinding.root, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
            return
        }
        val chapterId = readerState?.chapterId ?: run {
            Snackbar.make(viewBinding.root, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
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
            Snackbar.make(viewBinding.root, R.string.download_started, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun toggleIntroMarker() {
        if (currentMangaId == 0L) return
        if (introEndMs > 0) {
            introEndMs = 0L
            appSettings.clearIntroEndMs(currentMangaId)
            Snackbar.make(viewBinding.root, R.string.video_skip_intro_cleared, Snackbar.LENGTH_SHORT).show()
        } else {
            val pos = mpvPlayer?.positionMs ?: return
            introEndMs = pos
            appSettings.setIntroEndMs(currentMangaId, pos)
            Snackbar.make(
                viewBinding.root,
                getString(R.string.video_skip_intro_set, formatTimeMs(pos)),
                Snackbar.LENGTH_SHORT,
            ).show()
        }
        updateIntroOutroButtonState()
    }

    private fun toggleOutroMarker() {
        if (currentMangaId == 0L) return
        if (outroStartMs > 0) {
            outroStartMs = 0L
            appSettings.clearOutroStartMs(currentMangaId)
            Snackbar.make(viewBinding.root, R.string.video_skip_outro_cleared, Snackbar.LENGTH_SHORT).show()
        } else {
            val pos = mpvPlayer?.positionMs ?: return
            outroStartMs = pos
            appSettings.setOutroStartMs(currentMangaId, pos)
            Snackbar.make(
                viewBinding.root,
                getString(R.string.video_skip_outro_set, formatTimeMs(pos)),
                Snackbar.LENGTH_SHORT,
            ).show()
        }
        updateIntroOutroButtonState()
    }

    private fun rebuildToolbarMenuForOrientation() {
        viewBinding.toolbar.menu.clear()
        val secondaryToolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(org.skepsun.kototoro.R.id.toolbar_secondary)
        secondaryToolbar?.menu?.clear()

        // 横屏时：inflate ?toolbar；竖屏时：inflate ?secondaryToolbar
        val targetToolbar = if (isLandscapeOrientation()) viewBinding.toolbar else secondaryToolbar

        if (targetToolbar != null) {
            targetToolbar.inflateMenu(org.skepsun.kototoro.R.menu.menu_video_player)
            spaceSwitcherDelegate.install(targetToolbar)
            // Force subtitle button to always show as icon (not in overflow)
            targetToolbar.menu.findItem(org.skepsun.kototoro.R.id.action_subtitle_track)?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    // 按方向调整工具栏高度：横屏恢复初始高度；竖屏需要显示辅助工具栏
    private fun adjustToolbarForOrientation() {
        val secondaryToolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(org.skepsun.kototoro.R.id.toolbar_secondary)

        if (isLandscapeOrientation()) {
            if (originalToolbarHeightPx > 0) {
                viewBinding.toolbar.layoutParams.height = originalToolbarHeightPx
            }
            secondaryToolbar?.isVisible = false
        } else {
            viewBinding.toolbar.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            secondaryToolbar?.isVisible = true
        }
        viewBinding.toolbar.minimumHeight = 0
        viewBinding.toolbar.requestLayout()
        secondaryToolbar?.requestLayout()
    }

    private fun rearrangeBottomToolbarForOrientation() {
        updateIntroOutroButtonState()
        updatePlaybackSpeedButton()
    }

    private fun updatePlaybackMenu() {
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        val isPlaying = mpvPlayer?.isPlaying == true
        allControllers().forEach { ctl ->
            val btn = ctl.findViewById<android.widget.ImageButton>(androidx.media3.ui.R.id.exo_play_pause) ?: return@forEach
            btn.isEnabled = true
            btn.isClickable = true
            btn.alpha = 1f
            btn.setImageResource(
                if (isPlaying) org.skepsun.kototoro.R.drawable.ic_pause
                else org.skepsun.kototoro.R.drawable.ic_play,
            )
            btn.contentDescription = getString(
                if (isPlaying) org.skepsun.kototoro.R.string.pause
                else org.skepsun.kototoro.R.string.play,
            )
        }
    }

    private fun applyControllerTint(ctl: PlayerControlView) {
        val white = Color.WHITE
        val whiteList = ColorStateList.valueOf(white)
        ctl.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)?.setTextColor(white)
        ctl.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)?.setTextColor(white)
        ctl.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.let { timeBar ->
            timeBar.setPlayedColor(white)
            timeBar.setBufferedColor(0x99FFFFFF.toInt())
            timeBar.setUnplayedColor(0x55FFFFFF.toInt())
            timeBar.setScrubberColor(white)
        }
        ctl.findViewById<android.widget.ImageButton>(androidx.media3.ui.R.id.exo_play_pause)
            ?.setColorFilter(white)
        val iconButtons = listOf(
            org.skepsun.kototoro.R.id.button_prev_chapter,
            org.skepsun.kototoro.R.id.button_next_chapter,
            org.skepsun.kototoro.R.id.button_pages_thumbs,
            org.skepsun.kototoro.R.id.button_quality,
            org.skepsun.kototoro.R.id.button_screen_lock,
        )
        iconButtons.forEach { id ->
            val view = ctl.findViewById<View>(id)
            when (view) {
                is android.widget.ImageButton -> view.setColorFilter(white)
                is com.google.android.material.button.MaterialButton -> view.iconTint = whiteList
            }
        }
    }

    private fun updateToolbarProgress() {
        val indicator = viewBinding.toolbarProgress
        // 顶部进度条不再显?
        indicator.isVisible = false
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

    // 手动驱动底部控制条（DefaultTimeBar 与已播放时长文本）定时刷?
    private fun updateControllerProgress() {
        if (!isUiVisible) return
        // 用户拖动时不要覆?timebar 的临时位置，否则会导致“拖不动/点不准”的体验
        if (isUserScrubbing) return
        val ctl = currentController() ?: return
        if (ctl.visibility != View.VISIBLE) return

        val p = mpvPlayer ?: return
        val duration = p.durationMs
        val position = p.positionMs
        val buffered = position

        // 判断是否需要显示小时位（总时长超?1 小时?
        val showHours = duration >= 3600_000L

        // 更新文本：当前播放位?+ 总时?
        runCatching {
            val posTv = ctl.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
            val durTv = ctl.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
            posTv?.text = formatTimeMs(position, forceHours = showHours)
            if (duration > 0) {
                durTv?.text = formatTimeMs(duration)
            }
        }

        // 更新时间条进?
        runCatching {
            val timeBar = ctl.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            val seekable = duration > 0
            timeBar?.isEnabled = seekable
            timeBar?.isClickable = seekable
            if (duration > 0) {
                timeBar?.setDuration(duration)
                timeBar?.setBufferedPosition(buffered)
                timeBar?.setPosition(position)
            }
        }
        updatePlayPauseButton()
    }

    private fun updateStatusBarByToolbar() {
        val color = android.graphics.Color.TRANSPARENT
        val isLight = ColorUtils.calculateLuminance(color) > 0.5
        WindowInsetsControllerCompat(window, viewBinding.root).setAppearanceLightStatusBars(isLight)
        viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.status_bar_scrim)?.setBackgroundColor(color)
        @Suppress("DEPRECATION")
        window.statusBarColor = color
        @Suppress("DEPRECATION")
        window.navigationBarColor = color
    }

    private fun applyPlaybackBackground() {
        val drawable = appSettings.videoBackground.resolve(this)
        viewBinding.root.background = drawable
        // SurfaceView 使用独立 Surface 渲染视频，保持背景透明避免遮挡画面
        viewBinding.playerView.background = null
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
        val (title, subtitle) = extractChapterInfo()
        supportActionBar?.title = title
        supportActionBar?.subtitle = subtitle
        viewBinding.toolbar.title = title
        viewBinding.toolbar.subtitle = subtitle
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
    private var subtitleOverlayView: android.widget.TextView? = null

    fun applySubtitleOverlayStyle() {
        val overlay = subtitleOverlayView ?: findViewById<android.widget.TextView>(org.skepsun.kototoro.R.id.subtitle_overlay)
        if (overlay == null) return
        subtitleOverlayView = overlay

        val settings = appSettings
        overlay.textSize = settings.videoSubtitleFontSize
        overlay.setTextColor(settings.videoSubtitleTextColor)

        // Background color
        overlay.setBackgroundColor(settings.videoSubtitleBgColor)

        // Shadow/Outline properties
        if (settings.videoSubtitleBorderSize > 0) {
            overlay.setShadowLayer(settings.videoSubtitleBorderSize, 0f, 0f, settings.videoSubtitleBorderColor)
        } else {
            overlay.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        }

        // Bold / Italic
        val style = if (settings.videoSubtitleBold && settings.videoSubtitleItalic) {
            android.graphics.Typeface.BOLD_ITALIC
        } else if (settings.videoSubtitleBold) {
            android.graphics.Typeface.BOLD
        } else if (settings.videoSubtitleItalic) {
            android.graphics.Typeface.ITALIC
        } else {
            android.graphics.Typeface.NORMAL
        }
        overlay.setTypeface(null, style)

        // Alignment (Gravity)
        val alignX = settings.videoSubtitleAlignX
        overlay.gravity = when (alignX) {
            0 -> android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            2 -> android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            else -> android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL
        }

        // Bottom Margin / Position
        val lp = overlay.layoutParams as? android.widget.FrameLayout.LayoutParams
        lp?.let {
            it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            val posDb = settings.videoSubtitlePosition
            val density = resources.displayMetrics.density
            val marginH = (32 * density).toInt()
            val marginV = (posDb * density).toInt()
            it.setMargins(marginH, 0, marginH, marginV)
            overlay.layoutParams = it
        }

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
            val overlay = subtitleOverlayView ?: findViewById<android.widget.TextView>(org.skepsun.kototoro.R.id.subtitle_overlay)
            if (overlay == null) return@runOnUiThread
            subtitleOverlayView = overlay

            if (text.isNullOrBlank()) {
                overlay.visibility = android.view.View.GONE
                overlay.text = ""
            } else {
                applySubtitleOverlayStyle()
                overlay.text = text
                overlay.visibility = android.view.View.VISIBLE
                overlay.bringToFront()
            }
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
        viewBinding.root.removeCallbacks(playbackStartupTimeoutRunnable)
        viewBinding.root.postDelayed(playbackStartupTimeoutRunnable, startupTimeoutMs)
    }

    private fun cancelPlaybackStartupTimeout() {
        viewBinding.root.removeCallbacks(playbackStartupTimeoutRunnable)
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
        Snackbar.make(viewBinding.root, messageRes, Snackbar.LENGTH_LONG)
            .setAction(R.string.settings) {
                showVideoSettingsPanel()
            }
            .show()
    }

    private fun showPlaybackErrorHintOnce(category: PlaybackFailureCategory) {
        if (!shownPlaybackErrorHints.add(category)) return
        val messageRes = when (category) {
            PlaybackFailureCategory.NETWORK_OR_SOURCE -> R.string.network_error
            PlaybackFailureCategory.COMPATIBILITY -> R.string.error_occurred
            PlaybackFailureCategory.UNKNOWN -> R.string.error_occurred
        }
        Snackbar.make(viewBinding.root, messageRes, Snackbar.LENGTH_LONG)
            .setAction(R.string.settings) {
                showVideoSettingsPanel()
            }
            .show()
    }

    private fun showVideoSettingsPanel() {
        val toolbar = if (isLandscapeOrientation()) {
            viewBinding.toolbar
        } else {
            findViewById<com.google.android.material.appbar.MaterialToolbar>(org.skepsun.kototoro.R.id.toolbar_secondary)
                ?: viewBinding.toolbar
        }
        val anchor = toolbar.menuView?.findViewById<View>(org.skepsun.kototoro.R.id.action_settings) ?: toolbar
        showPlayerSettingsPopup(anchor, buildPlayerSettingsActions())
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
            Snackbar.make(
                viewBinding.root,
                org.skepsun.kototoro.R.string.video_no_subtitle_tracks,
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val labels = arrayOf(getString(org.skepsun.kototoro.R.string.video_subtitle_off)) +
            tracks.map { it.displayName() }.toTypedArray()
        val selectedTrack = tracks.indexOfFirst { it.isSelected }
        val checked = if (selectedTrack >= 0) selectedTrack + 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle(org.skepsun.kototoro.R.string.video_subtitle_track)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
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
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAudioTrackDialog() {
        val player = mpvPlayer ?: return
        val tracks = player.getAudioTracks()
        if (tracks.isEmpty()) {
            Snackbar.make(
                viewBinding.root,
                org.skepsun.kototoro.R.string.video_no_audio_tracks,
                Snackbar.LENGTH_SHORT,
            ).show()
            return
        }
        val labels = tracks.map { it.displayName() }.toTypedArray()
        val checked = tracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(org.skepsun.kototoro.R.string.video_audio_track)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                player.setAudioTrack(tracks[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    fun showQualityDialog() {
        if (availableVideos.isEmpty()) {
            Snackbar.make(
                viewBinding.root,
                org.skepsun.kototoro.R.string.operation_not_supported,
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val titles = availableVideos.mapIndexed { index, video ->
            video.qualityDisplayLabel(index)
        }.toTypedArray()
        val selected = currentVideoIndex.coerceIn(0, titles.lastIndex)
        MaterialAlertDialogBuilder(this)
            .setTitle(org.skepsun.kototoro.R.string.video_quality)
            .setSingleChoiceItems(titles, selected) { dialog, which ->
                if (which == currentVideoIndex) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                val video = availableVideos[which]
                val resumeMs = mpvPlayer?.positionMs ?: 0L
                currentVideoIndex = which
                updateQualityButtonLabel()
                pendingExternalSubtitles = video.subtitleTracks
                pendingExternalAudio = video.audioTracks
                val repo = currentVideoSource?.let { src -> mangaRepositoryFactory.create(src) }
                val mergedHeaders = mergeHeaders(repo?.getRequestHeaders(), headersToMap(video.headers))
                startMpvPlayback(
                    video.videoUrl,
                    currentVideoSource,
                    mergedHeaders,
                    resumeMs,
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_aspect_ratio)
            .setSingleChoiceItems(labels, checked) { _, which ->
                appSettings.videoAspectRatio = which
                applyAspectRatio()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPlaybackSpeedDialog() {
        val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { "%.2fx".format(it) }.toTypedArray()
        val current = appSettings.videoPlaybackSpeed
        val checked = options.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_playback_speed)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val speed = options[which]
                appSettings.videoPlaybackSpeed = speed
                applyPlaybackSpeed(speed)
                updatePlaybackSpeedButton()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDefaultPlaybackSpeedDialog() {
        val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { "%.2fx".format(it) }.toTypedArray()
        val current = appSettings.videoDefaultSpeed
        val checked = options.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_default_speed)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                appSettings.videoDefaultSpeed = options[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onSelect(options[which] * 1000)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVideoSuperResolutionSheet() {
        val tag = "VideoSuperResolutionSheet"
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(tag) == null) {
            VideoSuperResolutionSheet().show(fm, tag)
        }
    }

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
        val speed = appSettings.videoPlaybackSpeed
        val label = "%.2fx".format(speed)
        allControllers().forEach { ctl ->
            ctl.findViewById<com.google.android.material.button.MaterialButton>(
                org.skepsun.kototoro.R.id.button_playback_speed,
            )?.text = label
        }
    }

    override fun onStop() {
        viewBinding.root.removeCallbacks(hideUiRunnable)
        viewBinding.root.removeCallbacks(progressUpdateRunnable)
        viewBinding.root.removeCallbacks(controllerProgressRunnable)
        viewBinding.root.removeCallbacks(progressSaveRunnable)
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
        viewBinding.root.removeCallbacks(hideUiRunnable)
        viewBinding.root.removeCallbacks(progressUpdateRunnable)
        viewBinding.root.removeCallbacks(controllerProgressRunnable)
        viewBinding.root.removeCallbacks(progressSaveRunnable)
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
            Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
            return
        }
        val resumeMs = mpvPlayer?.positionMs ?: 0L
        startMpvPlayback(url, currentVideoSource, currentMediaHeaders, resumeMs)
    }

    private fun openVideoDetails() {
        val dialogView = layoutInflater.inflate(org.skepsun.kototoro.R.layout.dialog_video_player_info, null)
        dialogView.findViewById<android.widget.TextView>(org.skepsun.kototoro.R.id.text_video_info).text = buildVideoDetailsText()
        val dialog = MaterialAlertDialogBuilder(this, org.skepsun.kototoro.R.style.ThemeOverlay_Kototoro_VideoInfoDialog)
            .setView(dialogView)
            .create()
        dialogView.findViewById<android.widget.ImageButton>(org.skepsun.kototoro.R.id.button_close).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
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
            Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
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
            viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.top_gradient)?.isVisible = false
            viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.bottom_gradient)?.isVisible = false
        } else {
            applyGradientAlpha()
        }
    }

    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val surfaceView = findViewById<SurfaceView>(org.skepsun.kototoro.R.id.player_view) ?: return
        if (surfaceView.width <= 0 || surfaceView.height <= 0) {
            Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
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
                    Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
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
            Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.saved, Snackbar.LENGTH_SHORT).show()
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
            Snackbar.make(viewBinding.root, org.skepsun.kototoro.R.string.video_danmaku_enabled, Snackbar.LENGTH_SHORT).show()
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

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val type = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        val bars = insets.getInsets(type)
        val taskbarInsets = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
        val mandatoryGestureInsets = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        val cutoutInsets = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val bottomSafeInset = maxOf(
            bars.bottom,
            taskbarInsets.bottom,
            mandatoryGestureInsets.bottom,
            cutoutInsets.bottom,
        )
        // 顶部工具栏容器：使用外边距对齐系统栏高度，避免整体内容（如次级工具栏）侵入状态栏
        viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.toolbar_container)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin = bars.left
            rightMargin = bars.right
            topMargin = bars.top
        }
        viewBinding.toolbar.updatePadding(
            left = 0,
            right = 0,
            top = 0,
        )
        // 更新状态栏遮罩高度与左右边距以匹配系统?
        viewBinding.root.findViewById<View>(org.skepsun.kototoro.R.id.status_bar_scrim)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = bars.top
            leftMargin = bars.left
            rightMargin = bars.right
        }
        // ?DockedToolbar 与系统导航栏视觉合并：使用内边距吸收导航栏高度，避免底部留白
        allControllers().forEach { ctl ->
            val dockedToolbar = ctl.findViewById<View>(org.skepsun.kototoro.R.id.toolbar_docked)
            dockedToolbar?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left
                rightMargin = bars.right
                bottomMargin = 0
            }
            dockedToolbar?.updatePadding(bottom = bottomSafeInset)
        }
        // PlayerView 内容保持与左右系统栏对齐，底部不再额外内边距，避免与 DockedToolbar 重叠留白
        findViewById<View>(org.skepsun.kototoro.R.id.player_view).updatePadding(
            left = bars.left,
            right = bars.right,
            bottom = 0,
        )
        return insets.consumeAll(type)
    }

    // ReaderNavigationCallback implementation
    override fun onPageSelected(page: ReaderPage): Boolean {
        // Video player doesn't support page-level navigation
        return false
    }

    override fun onChapterSelected(chapter: ContentChapter): Boolean {
        // Handle chapter selection from ChaptersPagesSheet
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
                    chaptersViewModel.setCurrentChapter(chapter.id)
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
                    Snackbar.make(
                        viewBinding.root,
                        org.skepsun.kototoro.R.string.error_occurred,
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "Error loading chapter", e)
                Snackbar.make(
                    viewBinding.root,
                    org.skepsun.kototoro.R.string.error_occurred,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
        
        return true // Indicate we handled the selection
    }

    private fun updateChapterNavButtons() {
        // 从 ViewModel 读取实时章节列表，而不是 intent 启动时的快照
        val chapters = chaptersViewModel.chapters.value.map { it.chapter }
        if (chapters.isEmpty()) {
            allControllers().forEach { ctl ->
                ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_prev_chapter)?.apply {
                    isEnabled = false
                    alpha = 0.4f
                }
                ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_next_chapter)?.apply {
                    isEnabled = false
                    alpha = 0.4f
                }
            }
            return
        }

        val currentId = readerState?.chapterId ?: chapters.first().id
        val currentIndex = chapters.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex < chapters.lastIndex

        allControllers().forEach { ctl ->
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_prev_chapter)?.apply {
                isEnabled = hasPrev
                alpha = if (hasPrev) 1f else 0.4f
            }
            ctl.findViewById<View>(org.skepsun.kototoro.R.id.button_next_chapter)?.apply {
                isEnabled = hasNext
                alpha = if (hasNext) 1f else 0.4f
            }
        }
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
        val layout = viewBinding.seekFeedbackLayout ?: return
        val textTv = viewBinding.seekFeedbackText ?: return
        val progressInd = viewBinding.seekFeedbackProgress ?: return

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
        
        textTv.text = if (deltaStr.isNotEmpty()) "$deltaStr\n$timeStr" else timeStr
        textTv.gravity = android.view.Gravity.CENTER

        progressInd.max = 1000
        progressInd.progress = if (durationMs > 0) ((posMs * 1000) / durationMs).toInt() else 0

        layout.alpha = 1f
        layout.visibility = android.view.View.VISIBLE

        // Explicitly update the bottom TimeBar during gesture.
        // We use viewBinding since PlayerControlView is not strictly tied to an ExoPlayer here.
        val progressView = currentController()
            ?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? androidx.media3.ui.TimeBar
        if (progressView != null && durationMs > 0) {
            progressView.setDuration(durationMs)
            progressView.setPosition(posMs)
        }
        val posView = currentController()?.findViewById<android.widget.TextView>(androidx.media3.ui.R.id.exo_position)
        if (posView != null) {
            posView.text = formatTimeMs(posMs, showHours)
        }
    }

    private fun hideSeekFeedback() {
        viewBinding.seekFeedbackLayout?.isVisible = false
    }

    private fun openInExternalPlayer() {
        val url = currentMediaUrl
        if (url.isNullOrBlank()) {
            Snackbar.make(viewBinding.root, R.string.no_video_loaded, Snackbar.LENGTH_SHORT).show()
            return
        }
        val headers = currentMediaHeaders.orEmpty()
        val proxyUrl = videoLocalCacheProxy.getProxyUrl(url, headers, currentVideoSource)
        val title = viewBinding.toolbar.title?.toString()
        ExternalPlayerHelper.openInExternalPlayer(this, proxyUrl, title)
    }

    private fun showDlnaDeviceSheet() {
        val url = currentMediaUrl
        if (url.isNullOrBlank()) {
            Snackbar.make(viewBinding.root, R.string.no_video_loaded, Snackbar.LENGTH_SHORT).show()
            return
        }
        val headers = currentMediaHeaders.orEmpty()
        val positionMs = mpvPlayer?.positionMs ?: 0L
        val tag = "DlnaDeviceSheet"
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(tag) == null) {
            val sheet = DlnaDeviceSheet.newInstance(url, headers, positionMs)
            sheet.onCastStarted = {
                // Pause local playback when casting starts
                runOnUiThread { mpvPlayer?.pause() }
            }
            sheet.show(fm, tag)
        }
    }
}

package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.desktop.player.MpvEnhancement
import org.skepsun.kototoro.desktop.player.MpvEnhancementMode
import org.skepsun.kototoro.desktop.player.MpvInput
import org.skepsun.kototoro.desktop.player.MpvLocator
import org.skepsun.kototoro.desktop.player.MpvShaderLibrary
import org.skepsun.kototoro.desktop.player.MpvPlayer
import org.skepsun.kototoro.desktop.player.MpvState
import org.skepsun.kototoro.video.ui.compose.VideoSeekFeedback
import org.skepsun.kototoro.video.ui.compose.VideoSeekFeedbackState
import org.skepsun.kototoro.video.ui.compose.formatPlaybackTime
import org.skepsun.kototoro.video.ui.compose.videoSeekFeedbackState
import java.awt.Canvas
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

private val PlayerBackground = Color(0xFF0E1116)
private val PlayerPanel = Color(0xFF171B22)
private val PlayerText = Color(0xFFE8EBF0)
private val PlayerMuted = Color(0xFF9AA3B2)
private val Speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/**
 * Android's `video_background`: the colour around the video, behind the letterbox bars and the panels. Android's default
 * resolves to the theme background, which is what [PlayerBackground] is on this always-dark player.
 */
@Composable
private fun rememberVideoBackdrop(state: DesktopAppState): Color {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val appDark = when (state.appearance) {
        DesktopAppearance.LIGHT -> false
        DesktopAppearance.DARK -> true
        else -> systemDark
    }
    val background = state.videoSettings.background
    return remember(background, appDark) {
        if (background == DesktopReaderBackground.DEFAULT) PlayerBackground else background.color(appDark)
    }
}

/** A heavyweight canvas whose native window mpv renders into; [onWindow] gets the handle each time one exists. */
private class VideoCanvas(private val onWindow: (Long?) -> Unit) : Canvas() {
    init { background = java.awt.Color.BLACK }
    override fun addNotify() {
        super.addNotify()
        onWindow(Native.getComponentID(this))
    }
    override fun removeNotify() {
        onWindow(null)
        super.removeNotify()
    }
}

/** A lightweight AWT surface for libmpv render API frames; no Cocoa window pointer escapes into mpv. */
private class MacVideoPanel : javax.swing.JPanel() {
    @Volatile var frame: java.awt.image.BufferedImage? = null
    init { background = java.awt.Color.BLACK }
    override fun paintComponent(graphics: java.awt.Graphics) {
        super.paintComponent(graphics)
        frame?.let { graphics.drawImage(it, 0, 0, width, height, null) }
    }
}

private fun createVideoPlayer(library: Path, window: Long?, decoderMode: DesktopVideoDecoderMode): MpvPlayer {
    // Support aid: -Dkototoro.mpv.log=<file> keeps mpv's own verbose log.
    val diagnostics = System.getProperty("kototoro.mpv.log")?.takeIf(String::isNotBlank)
        ?.let { mapOf("log-file" to it, "msg-level" to "all=v") }.orEmpty()
    // and -Dkototoro.mpv.options=key=value;key=value adds mpv options (support / troubleshooting).
    val extra = System.getProperty("kototoro.mpv.options").orEmpty().split(';').filter { '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    // The playback preference chooses the decoder; an operator-supplied option still wins over it.
    val decoder = decoderMode.mpvOptions
    return MpvPlayer(library, window, diagnostics + decoder + extra,
        renderMode = MpvLocator.defaultRenderMode)
}

/**
 * The episode player: mpv draws into a native window inside the Compose layout, everything else is Compose. Native
 * calls that can wait on the window thread (creating and destroying the player) never run on the UI thread.
 */
@Composable
internal fun DesktopVideoPlayer(controller: DesktopController, state: DesktopAppState, closing: Boolean,
    fullscreen: Boolean = false, onToggleFullscreen: (() -> Unit)? = null) {
    val video = state.video ?: return
    val dataRoot = controller.session.storage.paths.root
    var library by remember { mutableStateOf(MpvLocator.find(dataRoot)) }
    val back: () -> Unit = {
        if (fullscreen) onToggleFullscreen?.invoke()
        controller.backToDetails()
    }
    Column(Modifier.fillMaxSize().background(rememberVideoBackdrop(state)).testTag("video-player")) {
        val found = library
        if (found == null || !MpvLocator.supportsEmbeddedPlayback) {
            MissingLibrary(controller, video, state, dataRoot, onRetry = { library = MpvLocator.find(dataRoot) }, onBack = back)
        } else {
            PlayerContent(controller, state, video, found, closing, fullscreen, onToggleFullscreen, back)
        }
    }
}

@Composable
private fun PlayerContent(controller: DesktopController, state: DesktopAppState, video: DesktopVideo, library: Path,
    closing: Boolean, fullscreen: Boolean, onToggleFullscreen: (() -> Unit)?, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MpvPlayer?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Where playback was when the native window went away, so a new window continues from there.
    var resumeAt by remember { mutableStateOf<Double?>(null) }
    val idle = remember { MutableStateFlow(MpvState()) }
    var canvasActive by remember { mutableStateOf(false) }
    val canvasGeneration = remember { java.util.concurrent.atomic.AtomicLong() }
    val canvas = remember {
        val onWindow: (Long?) -> Unit = { window ->
            val generation = canvasGeneration.incrementAndGet()
            canvasActive = window != null
            val previous = player
            player = null
            if (previous != null) {
                resumeAt = previous.state.value.position
                Thread({ runCatching { previous.close() } }, "mpv-close").start()
            }
            if (window != null) scope.launch(Dispatchers.IO) {
                try {
                    val created = createVideoPlayer(library, window, state.videoSettings.decoderMode)
                    var accepted = false
                    try {
                        javax.swing.SwingUtilities.invokeAndWait {
                            if (canvasActive && canvasGeneration.get() == generation) {
                                player = created
                                accepted = true
                            }
                        }
                    } finally { if (!accepted) created.close() }
                } catch (error: Throwable) {
                    failure = desktopText("video.playerStartFailed", error.message ?: error.javaClass.simpleName)
                }
            }
        }
        if (MpvLocator.usesRenderApi) MacVideoPanel() else VideoCanvas(onWindow)
    }
    LaunchedEffect(library) {
        if (!MpvLocator.usesRenderApi) return@LaunchedEffect
        var acquired: MpvPlayer? = null
        try {
            withContext(Dispatchers.IO) { acquired = createVideoPlayer(library, null, state.videoSettings.decoderMode) }
            player = acquired
            kotlinx.coroutines.awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = desktopText("video.playerStartFailed", error.message ?: error.javaClass.simpleName)
        } finally {
            player = null
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { acquired?.close() }
        }
    }
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        val surface = canvas as? MacVideoPanel ?: return@LaunchedEffect
        try {
            while (true) {
                val surfaceWidth = surface.width.coerceAtLeast(1)
                val surfaceHeight = surface.height.coerceAtLeast(1)
                val scale = minOf(1.0, 1920.0 / surfaceWidth, 1080.0 / surfaceHeight)
                val width = (surfaceWidth * scale).toInt().coerceAtLeast(1)
                val height = (surfaceHeight * scale).toInt().coerceAtLeast(1)
                val image = withContext(Dispatchers.IO) { current.renderFrame(width, height) }
                surface.frame = image
                surface.repaint()
                delay(33)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = desktopText("video.playerStartFailed", error.message ?: error.javaClass.simpleName)
        } finally { surface.frame = null }
    }
    DisposableEffect(Unit) {
        onDispose {
            canvasActive = false
            canvasGeneration.incrementAndGet()
            val current = player
            player = null
            if (!MpvLocator.usesRenderApi && current != null) {
                Thread({ runCatching { current.close() } }, "mpv-close").start()
            }
        }
    }
    val mpv by (player?.state ?: idle).collectAsState()
    val title = listOfNotNull(state.content?.title, video.chapter.title).joinToString(" · ")
    // A decoder change applies to a running player where libmpv allows it; a new window picks it up regardless.
    LaunchedEffect(player, state.videoSettings.decoderMode) {
        val current = player ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { current.setProperty("hwdec", state.videoSettings.decoderMode.mpvOptions.getValue("hwdec")) } }
    }
    LaunchedEffect(player, video.generation) {
        val current = player ?: return@LaunchedEffect
        val start = resumeAt ?: video.startSeconds
        resumeAt = null
        withContext(Dispatchers.IO) {
            runCatching { current.load(video.stream.toMpvStream(start, title)) }
                .onFailure { failure = desktopText("video.openFailed", it.message ?: it.javaClass.simpleName) }
        }
    }
    // Enhancement shaders follow the saved choice, for every stream this window plays.
    val shaders = remember { MpvShaderLibrary(controller.session.storage.paths.root.resolve("cache/mpv-shaders")) }
    var enhancementError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player, state.videoEnhancement) {
        val current = player ?: return@LaunchedEffect
        enhancementError = withContext(Dispatchers.IO) {
            runCatching { current.setShaders(shaders.files(state.videoEnhancement)) }.exceptionOrNull()
                ?.let { desktopText("video.enhancementUnavailable", it.message ?: it.javaClass.simpleName) }
        }
    }
    LaunchedEffect(mpv.position.toInt(), mpv.duration.toInt(), mpv.loaded) {
        if (mpv.loaded && mpv.duration > 0) controller.videoProgress(video.chapter.id, mpv.position, mpv.duration)
    }
    val hasPrevious = state.adjacentChapter(false) != null
    val hasNext = state.adjacentChapter(true) != null
    // A finished episode continues with the next one, as on Android.
    LaunchedEffect(mpv.ended) {
        if (mpv.ended && mpv.loaded && hasNext && !closing) controller.changeVideoEpisode(true)
    }
    var episodesOpen by remember { mutableStateOf(false) }
    var castOpen by remember { mutableStateOf(false) }
    var castAnchor by remember { mutableStateOf(Rect.Zero) }
    val castMenu = remember { PlayerMenuToggle() }
    // While a renderer plays, this window stays paused; when the cast ends it continues from the renderer's position.
    LaunchedEffect(player, state.cast) { if (state.cast != null) player?.let { p -> withContext(Dispatchers.IO) { runCatching { p.setPaused(true) } } } }
    LaunchedEffect(player, state.castEndedAt) {
        val (generation, position) = state.castEndedAt ?: return@LaunchedEffect
        if (generation != video.generation) return@LaunchedEffect
        player?.let { p -> withContext(Dispatchers.IO) { runCatching { p.seek(position); p.setPaused(false) } } }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(video.chapter.id) { focus.requestFocus() }
    fun control(block: (MpvPlayer) -> Unit) {
        val current = player ?: return
        scope.launch(Dispatchers.IO) { runCatching { block(current) } }
    }
    // The seeking bubble of Android's player; a fixed jump seeks and reports itself, a longer one replaces the last.
    var seekFeedback by remember { mutableStateOf<VideoSeekFeedbackState?>(null) }
    var seekFeedbackJob by remember { mutableStateOf<Job?>(null) }
    val jump: (Double) -> Unit = { offset ->
        desktopSeekFeedback(mpv.position, mpv.duration, offset)?.let { feedback ->
            control { it.seek(offset, relative = true) }
            seekFeedback = feedback
            seekFeedbackJob?.cancel()
            seekFeedbackJob = scope.launch { delay(1000); seekFeedback = null }
        }
    }
    // Android's player chrome: over the picture, shown by moving or clicking on it, gone after a while of playing.
    var chromeShown by remember { mutableStateOf(true) }
    var activity by remember { mutableStateOf(0) }
    var chromeHovered by remember { mutableStateOf(false) }
    val wake = { chromeShown = true; activity++ }
    // Windows reports a "move" to whatever window comes under a still pointer, as when the chrome hides; only a pointer
    // that really moved shows it again.
    val lastPointer = remember { arrayOfNulls<java.awt.Point>(1) }
    val pointerMoved = { at: java.awt.Point? ->
        if (at != null && at != lastPointer[0]) {
            val first = lastPointer[0] == null
            lastPointer[0] = at
            if (!first) wake()
        }
    }
    LaunchedEffect(chromeShown, activity, mpv.paused, mpv.loaded, chromeHovered, openPlayerMenus.value, episodesOpen,
        castOpen, state.cast) {
        if (chromeShown && mpv.loaded && !mpv.paused && !chromeHovered && openPlayerMenus.value == 0 && !episodesOpen &&
            !castOpen && state.cast == null) {
            delay(ChromeTimeoutMillis)
            chromeShown = false
        }
    }
    // A hidden window never reports the pointer leaving it.
    LaunchedEffect(chromeShown) { if (!chromeShown) chromeHovered = false }
    val toggleFullscreen: () -> Unit = { onToggleFullscreen?.invoke(); focus.requestFocus() }
    // mpv's window takes the mouse over the picture and reports it (MpvPlayer.input); the macOS panel is plain Swing.
    // One click shows or hides the chrome, a double click toggles fullscreen, the wheel sets the volume.
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        var pendingClick: Job? = null
        current.input.collect { input ->
            when (input) {
                MpvInput.MOVE -> pointerMoved(java.awt.MouseInfo.getPointerInfo()?.location)
                MpvInput.CLICK -> if (pendingClick?.isActive == true) pendingClick?.cancel() else {
                    pendingClick = launch {
                        delay(DoubleClickMillis)
                        if (chromeShown) chromeShown = false else wake()
                    }
                }
                MpvInput.DOUBLE_CLICK -> { pendingClick?.cancel(); toggleFullscreen() }
                MpvInput.WHEEL_UP -> { control { it.setVolume(mpv.volume + 5) }; wake() }
                MpvInput.WHEEL_DOWN -> { control { it.setVolume(mpv.volume - 5) }; wake() }
            }
            if (input != MpvInput.MOVE) focus.requestFocus()
        }
    }
    DisposableEffect(canvas) {
        val listener = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(event: java.awt.event.MouseEvent) { pointerMoved(event.locationOnScreen) }
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) toggleFullscreen() else if (chromeShown) chromeShown = false else wake()
            }
        }
        canvas.addMouseListener(listener)
        canvas.addMouseMotionListener(listener)
        onDispose { canvas.removeMouseListener(listener); canvas.removeMouseMotionListener(listener) }
    }
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed) return@onPreviewKeyEvent false
        wake()
        when (event.key) {
            Key.Spacebar, Key.K -> { control { it.togglePause() }; true }
            Key.DirectionLeft, Key.J -> { jump(if (event.isShiftPressed) -30.0 else -5.0); true }
            Key.DirectionRight, Key.L -> { jump(if (event.isShiftPressed) 30.0 else 5.0); true }
            Key.DirectionUp -> { control { it.setVolume(mpv.volume + 5) }; true }
            Key.DirectionDown -> { control { it.setVolume(mpv.volume - 5) }; true }
            Key.M -> { control { it.setMuted(!mpv.muted) }; true }
            Key.N -> { if (hasNext) controller.changeVideoEpisode(true); true }
            Key.F, Key.F11 -> { toggleFullscreen(); true }
            Key.Escape -> {
                when {
                    fullscreen -> toggleFullscreen()
                    episodesOpen -> episodesOpen = false
                    else -> back()
                }
                true
            }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxSize()) {
            val backdrop = rememberVideoBackdrop(state)
            Box(Modifier.weight(1f).fillMaxHeight().background(backdrop)) {
                SwingPanel(background = backdrop, factory = { canvas }, modifier = Modifier.fillMaxSize().testTag("video-surface"))
            }
            if (episodesOpen) EpisodeList(controller, state, video, Modifier.width(300.dp).fillMaxHeight())
        }
    }
    val hover = { inside: Boolean -> chromeHovered = inside; if (inside) wake() }
    PlayerOverlayWindow(canvas, chromeShown, PlayerOverlayPlacement.TOP, 64.dp, hover) {
        Row(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(back) { Text(desktopText("video.backToDetails"), color = PlayerText) }
            Column(Modifier.weight(1f)) {
                Text(state.content?.title.orEmpty(), color = PlayerText, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(video.chapter.title ?: desktopText("common.episodeTitle", video.chapter.number), color = PlayerMuted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton({ if (castMenu.mayOpen()) castOpen = true }, enabled = player != null,
                modifier = Modifier.screenAnchor(canvas) { castAnchor = it }.testTag("video-cast")) {
                Text(if (state.cast != null) desktopText("video.casting") else desktopText("video.cast"), color = PlayerText)
            }
            TextButton({ episodesOpen = !episodesOpen }, modifier = Modifier.testTag("video-episodes")) {
                Text(desktopText("video.episodes"), color = PlayerText)
            }
            TextButton(toggleFullscreen, enabled = onToggleFullscreen != null, modifier = Modifier.testTag("video-fullscreen")) {
                Text(if (fullscreen) desktopText("video.exitFullscreen") else desktopText("video.fullscreen"), color = PlayerText)
            }
        }
    }
    PlayerOverlayWindow(canvas, chromeShown, PlayerOverlayPlacement.BOTTOM, 176.dp, hover) {
        val active = state.cast
        if (active != null) {
            CastControls(active, onEnd = { controller.endCast() })
        } else {
            Controls(canvas, video, mpv, failure ?: enhancementError, hasPrevious, hasNext, !closing && player != null,
                controller, ::control, state.videoEnhancement, state.videoSettings, jump)
        }
    }
    // The seeking bubble sits in the middle of the picture, as on Android.
    PlayerOverlayWindow(canvas, seekFeedback != null, PlayerOverlayPlacement.CENTER, 120.dp, {}) {
        seekFeedback?.let { VideoSeekFeedback(it, Modifier.fillMaxSize()) }
    }
    if (castOpen) {
        CastDialog(canvas, castAnchor, onDismiss = { castOpen = false; castMenu.closed() }) { device ->
            castOpen = false
            castMenu.closed()
            controller.startCast(device)
        }
    }
}

@Composable
private fun EpisodeList(controller: DesktopController, state: DesktopAppState, video: DesktopVideo, modifier: Modifier) {
    val episodes = state.content?.chapters.orEmpty()
    LazyColumn(modifier.background(PlayerPanel).padding(8.dp).testTag("video-episode-list"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(episodes, key = { it.id }) { episode ->
            val current = episode.id == video.chapter.id
            Text(episode.title ?: desktopText("common.episodeTitle", episode.number), color = if (current) Color(0xFF7FD1B9) else PlayerText,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 2,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !current && !state.busy) { controller.read(episode) }
                    .padding(10.dp).testTag("video-episode:${episode.id}"))
        }
    }
}

@Composable
private fun Controls(host: java.awt.Component, video: DesktopVideo, mpv: MpvState, failure: String?, hasPrevious: Boolean,
    hasNext: Boolean, enabled: Boolean, controller: DesktopController, control: ((MpvPlayer) -> Unit) -> Unit,
    enhancement: MpvEnhancement, settings: DesktopVideoSettings, jump: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("video-controls")) {
        // Android draws a black gradient out of the control bar (`bg_video_bottom_gradient`), the second opacity.
        if (settings.gradientOpacity > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = settings.gradientOpacity)),
                        ),
                    ),
            )
        }
        ControlsBar(host, video, mpv, failure, hasPrevious, hasNext, enabled, controller, control, enhancement, settings, jump)
    }
}

/** The bar itself: its panel is translucent by Android's `video_controls_alpha`, so the video shows through. */
@Composable
private fun ControlsBar(host: java.awt.Component, video: DesktopVideo, mpv: MpvState, failure: String?,
    hasPrevious: Boolean, hasNext: Boolean, enabled: Boolean, controller: DesktopController,
    control: ((MpvPlayer) -> Unit) -> Unit, enhancement: MpvEnhancement, settings: DesktopVideoSettings,
    jump: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(PlayerPanel.copy(alpha = settings.controlsOpacity))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val status = failure ?: mpv.error ?: when {
            mpv.loading -> desktopText("video.loading")
            mpv.buffering -> desktopText("video.buffering")
            else -> null
        }
        status?.let { Text(it, color = if (failure != null || mpv.error != null) Color(0xFFFF8A80) else PlayerMuted,
            fontSize = 12.sp, modifier = Modifier.testTag("video-status")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatPlaybackTime(((dragging?.toDouble() ?: mpv.position) * 1000).toLong(), padMinutes = true),
                color = PlayerText, fontSize = 13.sp)
            Slider(
                value = (dragging ?: mpv.position.toFloat()).coerceIn(0f, mpv.duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { target -> control { it.seek(target.toDouble()) } }; dragging = null },
                valueRange = 0f..mpv.duration.toFloat().coerceAtLeast(1f),
                enabled = enabled && mpv.duration > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("video-seek"),
            )
            Text(formatPlaybackTime((mpv.duration * 1000).toLong(), padMinutes = true), color = PlayerText, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerButton(desktopText("video.previousEpisode"), enabled && hasPrevious) { controller.changeVideoEpisode(false) }
            PlayerButton("-10s", enabled, "video-rewind") { jump(-10.0) }
            PlayerButton(if (mpv.paused) desktopText("video.play") else desktopText("video.pause"), enabled, "video-play") { control { it.togglePause() } }
            PlayerButton("+10s", enabled, "video-forward") { jump(10.0) }
            PlayerButton(desktopText("video.nextEpisode"), enabled && hasNext) { controller.changeVideoEpisode(true) }
            Spacer(Modifier.weight(1f))
            Menu(host, desktopText("video.quality", video.label(video.selected)), video.streams.indices.map { video.label(it) to (it == video.selected) },
                enabled && video.streams.size > 1, "video-quality") { controller.selectVideoStream(it) }
            Menu(host, desktopText("video.subtitles"), listOf(desktopText("common.close") to mpv.subtitles.none { it.selected }) + mpv.subtitles.map { it.label to it.selected },
                enabled && mpv.subtitles.isNotEmpty(), "video-subtitles") { index ->
                control { it.selectSubtitle(if (index == 0) null else mpv.subtitles[index - 1].id) }
            }
            Menu(host, desktopText("video.audio"), mpv.audio.map { it.label to it.selected }, enabled && mpv.audio.size > 1, "video-audio") { index ->
                control { it.selectAudio(mpv.audio[index].id) }
            }
            Menu(host, "${trimSpeed(mpv.speed)}x", Speeds.map { "${trimSpeed(it)}x" to (it == mpv.speed) }, enabled, "video-speed") { index ->
                control { it.setSpeed(Speeds[index]) }
            }
            EnhancementMenu(host, enhancement, enabled) { controller.setVideoEnhancement(it) }
            PlayerButton(if (mpv.muted) desktopText("video.unmute") else desktopText("video.mute"), enabled) { control { it.setMuted(!mpv.muted) } }
            Slider(mpv.volume.toFloat(), { volume -> control { it.setVolume(volume.toInt()) } }, valueRange = 0f..130f,
                enabled = enabled, modifier = Modifier.width(120.dp).testTag("video-volume"))
        }
    }
}

@Composable
private fun PlayerButton(label: String, enabled: Boolean, tag: String? = null, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Text(label, color = if (enabled) PlayerText else PlayerMuted)
    }
}

@Composable
private fun Menu(host: java.awt.Component, label: String, entries: List<Pair<String, Boolean>>, enabled: Boolean,
    tag: String, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val toggle = remember { PlayerMenuToggle() }
    TextButton({ if (toggle.mayOpen()) open = true }, enabled = enabled,
        modifier = Modifier.screenAnchor(host) { anchor = it }.testTag(tag)) {
        Text(label, color = if (enabled) PlayerText else PlayerMuted, maxLines = 1)
    }
    if (open) {
        val close = { open = false; toggle.closed() }
        PlayerMenuWindow(host, anchor, 240.dp, (entries.size * PlayerMenuRowHeight.value + 12).coerceAtMost(420f).dp, close) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries) { index, (title, selected) ->
                    PlayerMenuRow(title, selected, "$tag:$index") { close(); onSelect(index) }
                }
            }
        }
    }
}

/** Anime4K presets and FSR, as in the Android player's enhancement dialog; FSR adds its sharpness slider. */
@Composable
private fun EnhancementMenu(host: java.awt.Component, enhancement: MpvEnhancement, enabled: Boolean,
    onChange: (MpvEnhancement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val toggle = remember { PlayerMenuToggle() }
    TextButton({ if (toggle.mayOpen()) open = true }, enabled = enabled,
        modifier = Modifier.screenAnchor(host) { anchor = it }.testTag("video-enhancement")) {
        Text(if (enhancement.mode == MpvEnhancementMode.OFF) desktopText("video.enhancement") else enhancement.mode.title,
            color = if (enabled) PlayerText else PlayerMuted, maxLines = 1)
    }
    if (open) {
        val close = { open = false; toggle.closed() }
        // Sized for the FSR slider too, so choosing FSR does not have to resize the window.
        val height = MpvEnhancementMode.entries.size * PlayerMenuRowHeight.value + 150
        PlayerMenuWindow(host, anchor, 300.dp, height.coerceAtMost(520f).dp, close) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                MpvEnhancementMode.entries.forEach { mode ->
                    PlayerMenuRow(mode.title, mode == enhancement.mode, "video-enhancement:${mode.name}") {
                        onChange(enhancement.copy(mode = mode))
                        if (mode != MpvEnhancementMode.FSR) close()
                    }
                }
                if (enhancement.mode == MpvEnhancementMode.FSR) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(desktopText("video.fsrSharpness", (enhancement.fsrSharpness * 100).toInt()), color = PlayerText,
                            fontSize = 13.sp)
                        var draft by remember(enhancement.fsrSharpness) { mutableStateOf(enhancement.fsrSharpness) }
                        Slider(draft, { draft = it }, valueRange = 0f..1f,
                            onValueChangeFinished = { onChange(enhancement.copy(fsrSharpness = draft)) })
                    }
                }
                Text(desktopText("video.enhancementHint"), color = PlayerMuted, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

private val PlayerMenuRowHeight = 44.dp
private val PlayerMenuAccent = Color(0xFF7FD1B9)

/** Android's player menu row (`VideoSelectionDialog`): the chosen entry in the accent colour with a check. */
@Composable
private fun PlayerMenuRow(title: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = PlayerMenuRowHeight).clickable(onClick = onClick)
        .padding(horizontal = 16.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (selected) PlayerMenuAccent else PlayerText, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Icon(painterResource("icons/ic_check.svg"), null, tint = PlayerMenuAccent, modifier = Modifier.size(18.dp))
    }
}

/**
 * A menu window closes when it loses focus, which a click on its own button causes first; that click must not open
 * it again straight away.
 */
private class PlayerMenuToggle {
    private var closedAt = 0L
    fun closed() { closedAt = System.currentTimeMillis() }
    fun mayOpen() = System.currentTimeMillis() - closedAt > 300
}

/** Menus and their button live in different windows (the chrome is one, see [PlayerOverlayWindow]). */
private val LocalPlayerWindow = staticCompositionLocalOf<java.awt.Window?> { null }

/** The player menus open now: the chrome stays while one is open, since hiding it would close it too. */
private val openPlayerMenus = mutableStateOf(0)

private const val ChromeTimeoutMillis = 3000L
private const val DoubleClickMillis = 300L

/**
 * Reports where the element is on screen, in AWT's logical pixels: the window that draws it is the chrome
 * ([LocalPlayerWindow]) or, outside it, the window holding [host].
 */
@Composable
private fun Modifier.screenAnchor(host: java.awt.Component, onAnchor: (Rect) -> Unit): Modifier {
    val window = LocalPlayerWindow.current
    val density = LocalDensity.current.density
    return onGloballyPositioned { coordinates ->
        val root = (window ?: javax.swing.SwingUtilities.getWindowAncestor(host)) as? javax.swing.RootPaneContainer
        val origin = root?.contentPane?.takeIf { it.isShowing }?.locationOnScreen ?: return@onGloballyPositioned
        val bounds = coordinates.boundsInWindow()
        onAnchor(Rect(origin.x + bounds.left / density, origin.y + bounds.top / density,
            origin.x + bounds.right / density, origin.y + bounds.bottom / density))
    }
}

/**
 * A player menu floating over the picture, as Android's do. mpv draws into a heavyweight native window that stays above
 * every Compose layer of the main window, so the menu is a modeless undecorated window of its own, sized once next to
 * [anchor] (on screen, see [screenAnchor]). compose.layers.type=WINDOW gave every popup such a window, but those follow
 * their draw bounds and crashed skiko when one resized while drawing; this one never resizes. It closes on Escape and
 * when it loses focus, which also covers the main window moving under it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerMenuWindow(host: java.awt.Component, anchor: Rect, width: Dp, height: Dp, onDismiss: () -> Unit,
    content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val body by rememberUpdatedState(content)
    DisposableEffect(host) {
        val owner = javax.swing.SwingUtilities.getWindowAncestor(host)
        if (owner == null || anchor == Rect.Zero) {
            dismiss()
            return@DisposableEffect onDispose { }
        }
        openPlayerMenus.value++
        val w = width.value.roundToInt()
        val h = height.value.roundToInt()
        val screen = owner.graphicsConfiguration.bounds
        // Right-aligned with its button; above it (the bar sits under the picture), below when there is no room.
        val x = (anchor.right - w).roundToInt()
        val above = anchor.top - h - 6
        val y = if (above >= screen.y) above else anchor.bottom + 6
        val dialog = ComposeDialog(owner, java.awt.Dialog.ModalityType.MODELESS)
        dialog.isUndecorated = true
        dialog.setBounds(x.coerceIn(screen.x, screen.x + screen.width - w),
            y.roundToInt().coerceIn(screen.y, screen.y + screen.height - h), w, h)
        dialog.setContent(onPreviewKeyEvent = { event ->
            (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) dismiss() }
        }, onKeyEvent = { false }) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(PlayerPanel).border(1.dp, Color(0xFF2A303A))
                    .padding(vertical = 6.dp)) { body() }
            }
        }
        dialog.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
            override fun windowLostFocus(event: java.awt.event.WindowEvent) = dismiss()
        })
        dialog.isVisible = true
        onDispose {
            openPlayerMenus.value--
            dialog.dispose()
        }
    }
}

internal enum class PlayerOverlayPlacement { TOP, BOTTOM, CENTER }

/**
 * Part of the player's chrome over the picture: a transparent, non-focusable window of its own (for the reason in
 * [PlayerMenuWindow]) that follows [host] as it moves and resizes, [height] tall across its width ([CENTER] is a
 * 360-wide box in its middle). Hiding it keeps the window, so showing it again is immediate. Being non-focusable, a
 * click on it leaves the keyboard with the player.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerOverlayWindow(host: java.awt.Component, visible: Boolean, placement: PlayerOverlayPlacement,
    height: Dp, onHover: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val body by rememberUpdatedState(content)
    val hover by rememberUpdatedState(onHover)
    val wanted by rememberUpdatedState(visible)
    // Brings the window in line with the picture and [visible]; set once the effect below runs.
    val sync = remember { arrayOf<() -> Unit>({}) }
    DisposableEffect(host) {
        var owner: java.awt.Window? = null
        var window: ComposeDialog? = null
        // The canvas joins a window only after composition, and can leave it and come back: (re)create on demand.
        fun create(into: java.awt.Window) = ComposeDialog(into, java.awt.Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isTransparent = true
            focusableWindowState = false
            val self = this
            setContent {
                CompositionLocalProvider(LocalPlayerWindow provides self) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Box(Modifier.fillMaxSize()
                            .onPointerEvent(PointerEventType.Enter) { hover(true) }
                            .onPointerEvent(PointerEventType.Exit) { hover(false) },
                            contentAlignment = when (placement) {
                                PlayerOverlayPlacement.TOP -> Alignment.TopCenter
                                PlayerOverlayPlacement.BOTTOM -> Alignment.BottomCenter
                                PlayerOverlayPlacement.CENTER -> Alignment.Center
                            }) { body() }
                    }
                }
            }
        }
        val follow = object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(event: java.awt.event.ComponentEvent) = sync[0]()
            override fun componentResized(event: java.awt.event.ComponentEvent) = sync[0]()
        }
        sync[0] = sync@{
            val showing = host.isShowing
            val ancestor = javax.swing.SwingUtilities.getWindowAncestor(host)
            if (ancestor !== owner) {
                owner?.removeComponentListener(follow)
                window?.dispose()
                window = null
                owner = ancestor
                ancestor?.addComponentListener(follow)
            }
            if (!showing || ancestor == null) { window?.isVisible = false; return@sync }
            if (!wanted) { window?.isVisible = false; return@sync }
            val current = window ?: create(ancestor).also { window = it }
            val origin = host.locationOnScreen
            val h = height.value.roundToInt().coerceAtMost(host.height)
            when (placement) {
                PlayerOverlayPlacement.TOP -> current.setBounds(origin.x, origin.y, host.width, h)
                PlayerOverlayPlacement.BOTTOM -> current.setBounds(origin.x, origin.y + host.height - h, host.width, h)
                PlayerOverlayPlacement.CENTER -> {
                    val w = 360.coerceAtMost(host.width)
                    current.setBounds(origin.x + (host.width - w) / 2, origin.y + (host.height - h) / 2, w, h)
                }
            }
            if (!current.isVisible) current.isVisible = true
        }
        val hierarchy = java.awt.event.HierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) sync[0]()
        }
        host.addComponentListener(follow)
        host.addHierarchyListener(hierarchy)
        sync[0]()
        onDispose {
            sync[0] = {}
            host.removeComponentListener(follow)
            host.removeHierarchyListener(hierarchy)
            owner?.removeComponentListener(follow)
            window?.dispose()
        }
    }
    LaunchedEffect(visible) { sync[0]() }
}
/** Shown instead of the player when no libmpv is installed: where to put it, and ways to watch meanwhile. */
@Composable
private fun MissingLibrary(controller: DesktopController, video: DesktopVideo, state: DesktopAppState, dataRoot: Path,
    onRetry: () -> Unit, onBack: () -> Unit) {
    val folder = MpvLocator.suggestedDirectory(dataRoot)
    var external by remember { mutableStateOf(MpvLocator.findExecutable()) }
    var notice by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(color = PlayerPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(24.dp).testTag("video-missing-library"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (MpvLocator.supportsEmbeddedPlayback) desktopText("video.libmpvMissing")
                    else desktopText("video.externalRequired"),
                    color = PlayerText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (MpvLocator.supportsEmbeddedPlayback) {
                    // A Linux distribution packages libmpv; telling those users to fetch a Windows DLL would be wrong.
                    if (MpvLocator.packagedByDistribution) desktopText("video.libmpvHintLinux")
                    else desktopText("video.libmpvHint")
                } else desktopText("video.externalHint"), color = PlayerMuted)
                if (MpvLocator.supportsEmbeddedPlayback) Text(folder.toString(), color = PlayerText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (MpvLocator.supportsEmbeddedPlayback) {
                        Button({
                            runCatching { Files.createDirectories(folder); java.awt.Desktop.getDesktop().open(folder.toFile()) }
                                .onFailure { notice = desktopText("video.openFolderFailed", it.message ?: it.javaClass.simpleName) }
                        }) { Text(desktopText("video.openFolder")) }
                    }
                    OutlinedButton({ onRetry(); external = MpvLocator.findExecutable() },
                        modifier = Modifier.testTag("video-retry-library")) { Text(desktopText("video.detectAgain")) }
                    OutlinedButton({
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(video.stream.url), null)
                        notice = desktopText("video.urlCopied")
                    }) { Text(desktopText("video.copyUrl")) }
                    external?.let { executable ->
                        OutlinedButton({
                            val stream = video.stream.toMpvStream(video.startSeconds,
                                listOfNotNull(state.content?.title, video.chapter.title).joinToString(" · "))
                            runCatching { ProcessBuilder(MpvLocator.commandLine(executable, stream)).start() }
                                .onSuccess { notice = desktopText("video.externalOpened") }
                                .onFailure { notice = desktopText("video.externalFailed", it.message ?: it.javaClass.simpleName) }
                        }) { Text(desktopText("video.playExternal")) }
                    }
                    TextButton(onBack) { Text(desktopText("video.backToDetails")) }
                }
                notice?.let { Text(it, color = PlayerMuted) }
            }
        }
    }
}

/**
 * The bubble a fixed jump shows, or null while the length is unknown (nothing to seek in yet). The jump sentence is
 * Android's own wording, so both players say the same thing; [videoSeekFeedbackState] keeps the rest identical.
 */
internal fun desktopSeekFeedback(position: Double, duration: Double, offset: Double): VideoSeekFeedbackState? {
    if (!(duration > 0.0) || offset == 0.0) return null
    val seconds = kotlin.math.abs(offset).toInt()
    val delta = AndroidStrings.format(
        if (offset > 0) "video_fast_forward_time" else "video_rewind_time", seconds.toString())
    val target = (position + offset).coerceIn(0.0, duration)
    return videoSeekFeedbackState((target * 1000).toLong(), (duration * 1000).toLong(), delta)
}

private fun trimSpeed(speed: Double) = if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString().trimEnd('0')

/**
 * One cast to a renderer: the relay that adds the stream's headers, the renderer, and its polled status.
 * Closing stops the renderer and the relay.
 */
class DesktopCast private constructor(
    val renderer: org.skepsun.kototoro.desktop.player.DlnaRenderer,
    private val proxy: org.skepsun.kototoro.desktop.player.DlnaStreamProxy,
) : AutoCloseable {
    val status = kotlinx.coroutines.flow.MutableStateFlow(org.skepsun.kototoro.desktop.player.DlnaStatus("TRANSITIONING", 0.0, 0.0))
    private val poller = Thread({
        while (!Thread.currentThread().isInterrupted) {
            runCatching { status.value = renderer.status() }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
        }
    }, "dlna-status").apply { isDaemon = true; start() }

    override fun close() {
        poller.interrupt()
        runCatching { renderer.stop() }
        proxy.close()
    }

    companion object {
        fun start(device: org.skepsun.kototoro.desktop.player.DlnaDevice, stream: org.skepsun.kototoro.core.source.SourcePage,
            title: String, position: Double): DesktopCast {
            val proxy = org.skepsun.kototoro.desktop.player.DlnaStreamProxy()
            try {
                val renderer = org.skepsun.kototoro.desktop.player.DlnaRenderer(device)
                val host = org.skepsun.kototoro.desktop.player.DlnaRenderer.localAddressFor(device)
                val url = proxy.register(stream.url, stream.headers.orEmpty(), host)
                renderer.load(url, title, org.skepsun.kototoro.desktop.player.DlnaRenderer.mimeOf(stream.url))
                renderer.play()
                if (position > 5) runCatching { renderer.seek(position) }
                return DesktopCast(renderer, proxy)
            } catch (error: Exception) {
                proxy.close()
                throw error
            }
        }
    }
}

/** Android's DLNA device dialog, as a player menu window under the cast button (a Compose dialog would sit under mpv). */
@Composable
private fun CastDialog(host: java.awt.Component, anchor: Rect, onDismiss: () -> Unit,
    onSelect: (org.skepsun.kototoro.desktop.player.DlnaDevice) -> Unit) {
    var devices by remember { mutableStateOf<List<org.skepsun.kototoro.desktop.player.DlnaDevice>?>(null) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        devices = null
        devices = withContext(Dispatchers.IO) { runCatching { org.skepsun.kototoro.desktop.player.DlnaDiscovery.discover() }.getOrDefault(emptyList()) }
    }
    PlayerMenuWindow(host, anchor, 380.dp, 320.dp, onDismiss) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).testTag("cast-dialog"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(desktopText("video.castDialogTitle"), color = PlayerText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val found = devices
                when {
                    found == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(desktopText("video.castSearching"), color = PlayerText)
                    }
                    found.isEmpty() -> Text(desktopText("video.castNotFound"), color = PlayerMuted)
                    else -> found.forEach { device ->
                        OutlinedButton({ onSelect(device) }, modifier = Modifier.fillMaxWidth().testTag("cast-device:${device.name}")) {
                            Text(device.name)
                        }
                    }
                }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton({ round++ }) { Text(desktopText("video.castSearchAgain")) }
                TextButton(onDismiss) { Text(desktopText("common.cancel")) }
            }
        }
    }
}

@Composable
private fun CastControls(cast: DesktopCast, onEnd: () -> Unit) {
    val status by cast.status.collectAsState()
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Float?>(null) }
    fun remote(block: (org.skepsun.kototoro.desktop.player.DlnaRenderer) -> Unit) {
        scope.launch(Dispatchers.IO) { runCatching { block(cast.renderer) } }
    }
    Column(Modifier.fillMaxWidth().background(PlayerPanel).padding(horizontal = 16.dp, vertical = 8.dp).testTag("cast-controls")) {
        Text(desktopText("video.castTo", cast.renderer.device.name, castState(status.state)), color = PlayerText, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatPlaybackTime(((dragging?.toDouble() ?: status.position) * 1000).toLong(), padMinutes = true),
                color = PlayerText, fontSize = 13.sp)
            Slider((dragging ?: status.position.toFloat()).coerceIn(0f, status.duration.toFloat().coerceAtLeast(1f)),
                { dragging = it }, valueRange = 0f..status.duration.toFloat().coerceAtLeast(1f), enabled = status.duration > 0,
                onValueChangeFinished = { dragging?.let { target -> remote { it.seek(target.toDouble()) } }; dragging = null },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(formatPlaybackTime((status.duration * 1000).toLong(), padMinutes = true), color = PlayerText, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerButton("-10s", true) { remote { it.seek((status.position - 10).coerceAtLeast(0.0)) } }
            PlayerButton(if (status.state == "PLAYING") desktopText("video.pause") else desktopText("video.play"), true, "cast-play") {
                remote { if (status.state == "PLAYING") it.pause() else it.play() }
            }
            PlayerButton("+10s", true) { remote { it.seek(status.position + 10) } }
            Spacer(Modifier.weight(1f))
            PlayerButton(desktopText("video.castEnd"), true, "cast-end") { onEnd() }
        }
    }
}

private fun castState(state: String) = when (state) {
    "PLAYING" -> desktopText("video.castState.PLAYING"); "PAUSED_PLAYBACK" -> desktopText("video.castState.PAUSED_PLAYBACK"); "STOPPED" -> desktopText("video.castState.STOPPED"); "TRANSITIONING" -> desktopText("video.castState.TRANSITIONING")
    "NO_MEDIA_PRESENT" -> desktopText("video.castState.NO_MEDIA_PRESENT"); else -> state
}

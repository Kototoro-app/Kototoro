package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.desktop.player.MpvEnhancement
import org.skepsun.kototoro.desktop.player.MpvEnhancementMode
import org.skepsun.kototoro.desktop.player.MpvLocator
import org.skepsun.kototoro.desktop.player.MpvShaderLibrary
import org.skepsun.kototoro.desktop.player.MpvPlayer
import org.skepsun.kototoro.desktop.player.MpvState
import java.awt.Canvas
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

private val PlayerBackground = Color(0xFF0E1116)
private val PlayerPanel = Color(0xFF171B22)
private val PlayerText = Color(0xFFE8EBF0)
private val PlayerMuted = Color(0xFF9AA3B2)
private val Speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

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
    Column(Modifier.fillMaxSize().background(PlayerBackground).testTag("video-player")) {
        val found = library
        if (found == null) {
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
    val canvas = remember {
        VideoCanvas { window ->
            val previous = player
            player = null
            if (previous != null) {
                resumeAt = previous.state.value.position
                Thread({ runCatching { previous.close() } }, "mpv-close").start()
            }
            if (window != null) scope.launch(Dispatchers.IO) {
                try {
                    // Support aid: -Dkototoro.mpv.log=<file> keeps mpv's own verbose log.
                    val diagnostics = System.getProperty("kototoro.mpv.log")?.takeIf(String::isNotBlank)
                        ?.let { mapOf("log-file" to it, "msg-level" to "all=v") }.orEmpty()
                    // and -Dkototoro.mpv.options=key=value;key=value adds mpv options (support / troubleshooting).
                    val extra = System.getProperty("kototoro.mpv.options").orEmpty().split(';').filter { '=' in it }
                        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                    player = MpvPlayer(library, window, diagnostics + extra)
                } catch (error: Throwable) {
                    failure = "播放器启动失败：${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val current = player
            player = null
            if (current != null) Thread({ runCatching { current.close() } }, "mpv-close").start()
        }
    }
    val mpv by (player?.state ?: idle).collectAsState()
    val title = listOfNotNull(state.content?.title, video.chapter.title).joinToString(" · ")
    LaunchedEffect(player, video.generation) {
        val current = player ?: return@LaunchedEffect
        val start = resumeAt ?: video.startSeconds
        resumeAt = null
        withContext(Dispatchers.IO) {
            runCatching { current.load(video.stream.toMpvStream(start, title)) }
                .onFailure { failure = "无法打开视频：${it.message}" }
        }
    }
    // Enhancement shaders follow the saved choice, for every stream this window plays.
    val shaders = remember { MpvShaderLibrary(controller.session.storage.paths.root.resolve("cache/mpv-shaders")) }
    var enhancementError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player, state.videoEnhancement) {
        val current = player ?: return@LaunchedEffect
        enhancementError = withContext(Dispatchers.IO) {
            runCatching { current.setShaders(shaders.files(state.videoEnhancement)) }.exceptionOrNull()
                ?.let { "画质增强不可用：${it.message}" }
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
    Column(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Spacebar, Key.K -> { control { it.togglePause() }; true }
            Key.DirectionLeft, Key.J -> { control { it.seek(if (event.isShiftPressed) -30.0 else -5.0, relative = true) }; true }
            Key.DirectionRight, Key.L -> { control { it.seek(if (event.isShiftPressed) 30.0 else 5.0, relative = true) }; true }
            Key.DirectionUp -> { control { it.setVolume(mpv.volume + 5) }; true }
            Key.DirectionDown -> { control { it.setVolume(mpv.volume - 5) }; true }
            Key.M -> { control { it.setMuted(!mpv.muted) }; true }
            Key.N -> { if (hasNext) controller.changeVideoEpisode(true); true }
            Key.F, Key.F11 -> { onToggleFullscreen?.invoke(); true }
            Key.Escape -> { if (episodesOpen) episodesOpen = false else back(); true }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(PlayerPanel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(back) { Text("返回详情", color = PlayerText) }
            Column(Modifier.weight(1f)) {
                Text(state.content?.title.orEmpty(), color = PlayerText, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(video.chapter.title ?: "第 ${video.chapter.number} 集", color = PlayerMuted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton({ castOpen = true }, enabled = player != null, modifier = Modifier.testTag("video-cast")) {
                Text(if (state.cast != null) "投屏中" else "投屏", color = PlayerText)
            }
            TextButton({ episodesOpen = !episodesOpen }, modifier = Modifier.testTag("video-episodes")) {
                Text("剧集", color = PlayerText)
            }
            TextButton({ onToggleFullscreen?.invoke() }, enabled = onToggleFullscreen != null) {
                Text(if (fullscreen) "退出全屏" else "全屏", color = PlayerText)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                SwingPanel(background = Color.Black, factory = { canvas }, modifier = Modifier.fillMaxSize().testTag("video-surface"))
            }
            if (episodesOpen) EpisodeList(controller, state, video, Modifier.width(300.dp).fillMaxHeight())
        }
        val active = state.cast
        if (active != null) {
            CastControls(active, onEnd = { controller.endCast() })
        } else {
            Controls(video, mpv, failure ?: enhancementError, hasPrevious, hasNext, !closing && player != null, controller, ::control,
                state.videoEnhancement)
        }
    }
    if (castOpen) {
        CastDialog(onDismiss = { castOpen = false }) { device ->
            castOpen = false
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
            Text(episode.title ?: "第 ${episode.number} 集", color = if (current) Color(0xFF7FD1B9) else PlayerText,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 2,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !current && !state.busy) { controller.read(episode) }
                    .padding(10.dp).testTag("video-episode:${episode.id}"))
        }
    }
}

@Composable
private fun Controls(video: DesktopVideo, mpv: MpvState, failure: String?, hasPrevious: Boolean, hasNext: Boolean,
    enabled: Boolean, controller: DesktopController, control: ((MpvPlayer) -> Unit) -> Unit, enhancement: MpvEnhancement) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(PlayerPanel).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val status = failure ?: mpv.error ?: when {
            mpv.loading -> "正在加载视频…"
            mpv.buffering -> "缓冲中…"
            else -> null
        }
        status?.let { Text(it, color = if (failure != null || mpv.error != null) Color(0xFFFF8A80) else PlayerMuted,
            fontSize = 12.sp, modifier = Modifier.testTag("video-status")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(dragging?.toDouble() ?: mpv.position), color = PlayerText, fontSize = 13.sp)
            Slider(
                value = (dragging ?: mpv.position.toFloat()).coerceIn(0f, mpv.duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { target -> control { it.seek(target.toDouble()) } }; dragging = null },
                valueRange = 0f..mpv.duration.toFloat().coerceAtLeast(1f),
                enabled = enabled && mpv.duration > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("video-seek"),
            )
            Text(clock(mpv.duration), color = PlayerText, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerButton("上一集", enabled && hasPrevious) { controller.changeVideoEpisode(false) }
            PlayerButton("-10s", enabled) { control { it.seek(-10.0, relative = true) } }
            PlayerButton(if (mpv.paused) "播放" else "暂停", enabled, "video-play") { control { it.togglePause() } }
            PlayerButton("+10s", enabled) { control { it.seek(10.0, relative = true) } }
            PlayerButton("下一集", enabled && hasNext) { controller.changeVideoEpisode(true) }
            Spacer(Modifier.weight(1f))
            Menu("画质 · ${video.label(video.selected)}", video.streams.indices.map { video.label(it) to (it == video.selected) },
                enabled && video.streams.size > 1, "video-quality") { controller.selectVideoStream(it) }
            Menu("字幕", listOf("关闭" to mpv.subtitles.none { it.selected }) + mpv.subtitles.map { it.label to it.selected },
                enabled && mpv.subtitles.isNotEmpty(), "video-subtitles") { index ->
                control { it.selectSubtitle(if (index == 0) null else mpv.subtitles[index - 1].id) }
            }
            Menu("音轨", mpv.audio.map { it.label to it.selected }, enabled && mpv.audio.size > 1, "video-audio") { index ->
                control { it.selectAudio(mpv.audio[index].id) }
            }
            Menu("${trimSpeed(mpv.speed)}x", Speeds.map { "${trimSpeed(it)}x" to (it == mpv.speed) }, enabled, "video-speed") { index ->
                control { it.setSpeed(Speeds[index]) }
            }
            EnhancementMenu(enhancement, enabled) { controller.setVideoEnhancement(it) }
            PlayerButton(if (mpv.muted) "取消静音" else "静音", enabled) { control { it.setMuted(!mpv.muted) } }
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
private fun Menu(label: String, entries: List<Pair<String, Boolean>>, enabled: Boolean, tag: String, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Text(label, color = if (enabled) PlayerText else PlayerMuted, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            entries.forEachIndexed { index, (title, selected) ->
                DropdownMenuItem({ open = false; onSelect(index) }) {
                    Text((if (selected) "✓ " else "   ") + title)
                }
            }
        }
    }
}

/** Anime4K presets and FSR, as in the Android player's enhancement dialog; FSR adds its sharpness slider. */
@Composable
private fun EnhancementMenu(enhancement: MpvEnhancement, enabled: Boolean, onChange: (MpvEnhancement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = enabled, modifier = Modifier.testTag("video-enhancement")) {
            Text(if (enhancement.mode == MpvEnhancementMode.OFF) "画质增强" else enhancement.mode.title,
                color = if (enabled) PlayerText else PlayerMuted, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            MpvEnhancementMode.entries.forEach { mode ->
                DropdownMenuItem({ onChange(enhancement.copy(mode = mode)); if (mode != MpvEnhancementMode.FSR) open = false },
                    modifier = Modifier.testTag("video-enhancement:${mode.name}")) {
                    Text((if (mode == enhancement.mode) "✓ " else "   ") + mode.title)
                }
            }
            if (enhancement.mode == MpvEnhancementMode.FSR) {
                Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("FSR 锐度 ${(enhancement.fsrSharpness * 100).toInt()}%", fontSize = 13.sp)
                    var draft by remember(enhancement.fsrSharpness) { mutableStateOf(enhancement.fsrSharpness) }
                    Slider(draft, { draft = it }, valueRange = 0f..1f,
                        onValueChangeFinished = { onChange(enhancement.copy(fsrSharpness = draft)) })
                }
            }
            Text("增强在显卡上实时运行；Anime4K 质量档对显卡要求较高。", fontSize = 12.sp,
                modifier = Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

/** Shown instead of the player when no libmpv is installed: where to put it, and ways to watch meanwhile. */
@Composable
private fun MissingLibrary(controller: DesktopController, video: DesktopVideo, state: DesktopAppState, dataRoot: Path,
    onRetry: () -> Unit, onBack: () -> Unit) {
    val folder = MpvLocator.suggestedDirectory(dataRoot)
    val external = remember { MpvLocator.findExecutable() }
    var notice by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(color = PlayerPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(24.dp).testTag("video-missing-library"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("需要 libmpv 才能在应用内播放", color = PlayerText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Kototoro 用 mpv 播放视频，但没有自带它。请下载 Windows 版 libmpv（例如 mpv-dev-lgpl-x86_64 压缩包），" +
                    "把其中的 libmpv-2.dll 放到下面的文件夹，然后点“重新检测”。", color = PlayerMuted)
                Text(folder.toString(), color = PlayerText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        runCatching { Files.createDirectories(folder); java.awt.Desktop.getDesktop().open(folder.toFile()) }
                            .onFailure { notice = "无法打开文件夹：${it.message}" }
                    }) { Text("打开文件夹") }
                    OutlinedButton(onRetry, modifier = Modifier.testTag("video-retry-library")) { Text("重新检测") }
                    OutlinedButton({
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(video.stream.url), null)
                        notice = "已复制视频地址（部分来源还需要请求头才能播放）"
                    }) { Text("复制视频地址") }
                    if (external != null) OutlinedButton({
                        val stream = video.stream.toMpvStream(video.startSeconds,
                            listOfNotNull(state.content?.title, video.chapter.title).joinToString(" · "))
                        runCatching { ProcessBuilder(MpvLocator.commandLine(external, stream)).start() }
                            .onSuccess { notice = "已在外部 mpv 中打开" }
                            .onFailure { notice = "无法启动 mpv：${it.message}" }
                    }) { Text("用外部 mpv 播放") }
                    TextButton(onBack) { Text("返回详情") }
                }
                notice?.let { Text(it, color = PlayerMuted) }
            }
        }
    }
}

private fun clock(seconds: Double): String {
    val total = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val rest = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%02d:%02d".format(minutes, rest)
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

@Composable
private fun CastDialog(onDismiss: () -> Unit, onSelect: (org.skepsun.kototoro.desktop.player.DlnaDevice) -> Unit) {
    var devices by remember { mutableStateOf<List<org.skepsun.kototoro.desktop.player.DlnaDevice>?>(null) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        devices = null
        devices = withContext(Dispatchers.IO) { runCatching { org.skepsun.kototoro.desktop.player.DlnaDiscovery.discover() }.getOrDefault(emptyList()) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("投屏到 DLNA 设备") }, text = {
        Column(Modifier.widthIn(min = 360.dp).testTag("cast-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val found = devices
            when {
                found == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("正在搜索局域网中的设备…")
                }
                found.isEmpty() -> Text("没有找到设备。请确认电视/盒子与电脑在同一网络，且 Windows 防火墙允许 Kototoro 的网络访问。")
                else -> found.forEach { device ->
                    OutlinedButton({ onSelect(device) }, modifier = Modifier.fillMaxWidth().testTag("cast-device:${device.name}")) {
                        Text(device.name)
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ round++ }) { Text("重新搜索") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
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
        Text("正在投屏到 ${cast.renderer.device.name} · ${castState(status.state)}", color = PlayerText, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(dragging?.toDouble() ?: status.position), color = PlayerText, fontSize = 13.sp)
            Slider((dragging ?: status.position.toFloat()).coerceIn(0f, status.duration.toFloat().coerceAtLeast(1f)),
                { dragging = it }, valueRange = 0f..status.duration.toFloat().coerceAtLeast(1f), enabled = status.duration > 0,
                onValueChangeFinished = { dragging?.let { target -> remote { it.seek(target.toDouble()) } }; dragging = null },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(clock(status.duration), color = PlayerText, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerButton("-10s", true) { remote { it.seek((status.position - 10).coerceAtLeast(0.0)) } }
            PlayerButton(if (status.state == "PLAYING") "暂停" else "播放", true, "cast-play") {
                remote { if (status.state == "PLAYING") it.pause() else it.play() }
            }
            PlayerButton("+10s", true) { remote { it.seek(status.position + 10) } }
            Spacer(Modifier.weight(1f))
            PlayerButton("结束投屏（本机继续）", true, "cast-end") { onEnd() }
        }
    }
}

private fun castState(state: String) = when (state) {
    "PLAYING" -> "播放中"; "PAUSED_PLAYBACK" -> "已暂停"; "STOPPED" -> "已停止"; "TRANSITIONING" -> "加载中"
    "NO_MEDIA_PRESENT" -> "无媒体"; else -> state
}
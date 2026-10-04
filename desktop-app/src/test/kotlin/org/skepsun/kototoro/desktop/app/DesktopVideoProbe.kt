package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.desktop.player.MpvLocator
import org.skepsun.kototoro.desktop.player.MpvPlayer
import org.skepsun.kototoro.desktop.player.MpvStream
import java.awt.Canvas
import java.awt.Container
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * A real window and a real libmpv (opt-in via `-PlibmpvDirectory`): an Aniyomi fixture episode is opened from details,
 * its stream (served locally, Referer required) must actually draw into the window, quality switching continues the
 * position, the next episode follows, and the watch position lands in history.
 */
internal object DesktopVideoProbe {
    fun run(args: Array<String>) {
        val root = Path.of(args[1])
        val library = requireNotNull(MpvLocator.find(root)) { "no libmpv for the video probe" }
        val clip = root.resolveSibling("video-probe-clip.mkv")
        MpvPlayer(library, options = mapOf("o" to clip.toString(), "ovc" to "mjpeg", "of" to "matroska",
            "keep-open" to "no")).use { encoder ->
            encoder.load(MpvStream("av://lavfi:testsrc=duration=40:size=320x240:rate=10"))
            waitUntil("clip encoded") { Files.isRegularFile(clip) && encoder.property("idle-active") == "yes" }
        }
        val bytes = Files.readAllBytes(clip)
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/clip.mkv") { exchange ->
                synchronized(requests) { requests += exchange.requestHeaders.getFirst("Referer").orEmpty() }
                if (exchange.requestHeaders.getFirst("Referer") != "https://anime.fixture/") {
                    exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext
                }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        System.setProperty("fixture.anime.video.url", "http://127.0.0.1:${server.address.port}/clip.mkv")
        System.setProperty("kototoro.mpv.log", Path.of(args[3]).resolve("video-mpv.log").toString())
        System.getProperty("kototoro.real.mpvoptions")?.let { System.setProperty("kototoro.mpv.options", it) }
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        var finished by mutableStateOf(false)
        val failure = AtomicReference<Throwable>()
        Thread({
            try { drive(controller, session, Path.of(args[3]), Path.of(System.getProperty("kototoro.desktop.anime.fixture.jar"))) }
            catch (error: Throwable) { failure.set(error) }
            finally { finished = true }
        }, "video-probe").apply { isDaemon = true; start() }
        application(exitProcessOnExit = false) {
            if (finished) exitApplication()
            else Window(onCloseRequest = ::exitApplication, title = "Kototoro video probe",
                state = rememberWindowState(width = 1280.dp, height = 860.dp)) { DesktopApp(controller) }
        }
        runBlocking { controller.shutdown() }
        server.stop(0)
        failure.get()?.let { throw it }
        check(synchronized(requests) { requests.isNotEmpty() && requests.all { it == "https://anime.fixture/" } }) { requests }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    private fun drive(controller: DesktopController, session: DesktopSession, reports: Path, fixture: Path) {
        fun idle() {
            waitUntil("controller idle") { !controller.state.value.busy }
            controller.state.value.error?.let { error(it) }
        }
        runBlocking { controller.importJar(fixture).join() }
        idle()
        val listing = controller.state.value.sources.single { it.ecosystem == SourceEcosystem.ANIYOMI }
        runBlocking { controller.selectSource(listing).join() }
        idle()
        runBlocking { controller.details(controller.state.value.items.first()).join() }
        idle()
        val content = requireNotNull(controller.state.value.content)
        val first = content.chapters!!.first()
        runBlocking { controller.read(first).join() }
        idle()
        check(controller.state.value.screen == DesktopScreen.VIDEO)
        waitUntil("playback starts", 30_000) { (controller.state.value.video?.position ?: 0.0) >= 3.0 }
        val video = requireNotNull(controller.state.value.video)
        check(video.duration in 39.0..41.0) { "duration ${video.duration}" }
        check(video.streams.size == 2)
        // mpv must draw into the app's own window: sample the video surface on screen.
        checkSurfacePainted(reports)
        // Enhancement: each preset must reach mpv's GPU pipeline: after the list is set, mpv compiles the shaders' own
        // code (dumped in its debug log) without errors.
        fun compiledAfter(marker: String, token: String): Boolean {
            val log = Files.readString(reports.resolve("video-mpv.log"))
            val start = log.lastIndexOf(marker).takeIf { it >= 0 } ?: return false
            val tail = log.substring(start)
            check(!tail.contains("[e][vo/gpu")) { tail.lines().filter { "[e][vo/gpu" in it }.take(5).joinToString("\n") }
            return tail.contains(token)
        }
        controller.setVideoEnhancement(org.skepsun.kototoro.desktop.player.MpvEnhancement(
            org.skepsun.kototoro.desktop.player.MpvEnhancementMode.ANIME4K_QUALITY))
        waitUntil("Anime4K passes compiled", 20_000) { compiledAfter("Upscale_CNN_x2_VL.glsl", "conv2d") }
        controller.setVideoEnhancement(org.skepsun.kototoro.desktop.player.MpvEnhancement(
            org.skepsun.kototoro.desktop.player.MpvEnhancementMode.FSR, 0.5f))
        waitUntil("FSR passes compiled", 20_000) { compiledAfter("FSR-1.00.glsl", "FSR_RCAS_LIMIT") }
        controller.setVideoEnhancement(org.skepsun.kototoro.desktop.player.MpvEnhancement())
        println("VIDEO_ENHANCEMENT Anime4K and FSR passes ran")
        val before = video.position
        controller.selectVideoStream(1)
        waitUntil("quality switch continues", 30_000) {
            val now = controller.state.value.video
            now != null && now.selected == 1 && now.position >= before
        }
        // DLNA: a renderer on the LAN plays the stream through the app's relay (which adds the Referer); this window
        // pauses, and ending the cast continues here from the renderer's position.
        FakeRenderer().use { renderer ->
            val device = org.skepsun.kototoro.desktop.player.DlnaDiscovery.discover(1500,
                listOf(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), renderer.port))).single()
            runBlocking { controller.startCast(device).join() }
            idle()
            check(controller.state.value.cast != null)
            waitUntil("renderer fetched the stream", 20_000) { renderer.fetched.get() > 0 }
            check(renderer.fetchStatus.get() == 200) { "relay status ${renderer.fetchStatus.get()}" }
            check(renderer.actions.take(2) == listOf("SetAVTransportURI", "Play")) { renderer.actions.toString() }
            Thread.sleep(800)
            val paused = controller.state.value.video!!.position
            Thread.sleep(1500)
            check(controller.state.value.video!!.position - paused < 0.5) { "local playback kept running while casting" }
            runBlocking { controller.endCast().join() }
            idle()
            check("Stop" in renderer.actions && controller.state.value.cast == null) { renderer.actions.toString() }
            waitUntil("local continues from the renderer", 20_000) { (controller.state.value.video?.position ?: 0.0) >= 12.0 }
            println("VIDEO_CAST relay=${renderer.fetchStatus.get()} bytes=${renderer.fetched.get()} actions=${renderer.actions}")
        }
        runBlocking { controller.backToDetails().join() }
        idle()
        val saved = runBlocking { session.library.progress(content.id) }
        check(saved != null && saved.chapterId == first.id && saved.page >= 3) { "history: $saved" }
        // Opening again resumes from history, and the next episode starts from its beginning.
        runBlocking { controller.read().join() }
        idle()
        check(controller.state.value.video!!.startSeconds >= 3.0)
        runBlocking { controller.changeVideoEpisode(true).join() }
        idle()
        val next = requireNotNull(controller.state.value.video)
        check(next.chapter.id != first.id && next.startSeconds == 0.0)
        waitUntil("next episode plays", 30_000) { (controller.state.value.video?.position ?: 0.0) >= 1.0 }
        runBlocking { controller.backToDetails().join() }
        idle()
        // The extension's own settings screen: choosing the mirror line changes what it offers first.
        runBlocking { controller.preferences().join() }
        idle()
        val screen = requireNotNull(controller.state.value.preferences)
        val line = screen.nodes.single { it.key == "preferred_line" }
        check(line.choices.map { it.value } == listOf("main", "mirror")) { line }
        runBlocking { controller.updatePreference(line, org.skepsun.kototoro.core.source.SourcePreferenceValue.Text("mirror")).join() }
        idle()
        check(controller.state.value.message == "设置已保存") { controller.state.value.message.orEmpty() }
        runBlocking { controller.details(content).join() }
        idle()
        runBlocking { controller.read(first).join() }
        idle()
        check(controller.state.value.video!!.label(0) == "备用线路") { controller.state.value.video!!.label(0) }
        runBlocking { controller.backToDetails().join() }
        idle()
    }

    /**
     * mpv must draw into the app's own window. Where the desktop can be captured, the surface must show the test
     * pattern's colours; where it cannot (a locked or remote session captures every window as black), mpv's own log must
     * show its video output configured for a window the size of our surface.
     */
    private fun checkSurfacePainted(reports: Path) {
        val bounds = AtomicReference<Rectangle>()
        val window = AtomicReference<Rectangle>()
        waitUntil("video surface shown") {
            SwingUtilities.invokeAndWait {
                val shown = AwtWindow.getWindows().filter { it.isShowing }
                window.set(shown.firstOrNull()?.let { Rectangle(it.locationOnScreen, it.size) })
                bounds.set(shown.flatMap { canvases(it) }
                    .firstOrNull { it.isShowing && it.width > 100 }?.let { Rectangle(it.locationOnScreen, it.size) })
            }
            bounds.get() != null && window.get() != null
        }
        val area = bounds.get()
        val whole = Robot().createScreenCapture(window.get())
        ImageIO.write(whole, "png", reports.resolve("video-window.png").toFile())
        fun colourful(image: java.awt.image.BufferedImage): Int {
            var count = 0
            for (x in 0 until image.width step 8) for (y in 0 until image.height step 8) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
                if (maxOf(r, g, b) > 24 || maxOf(r, g, b) - minOf(r, g, b) > 60) count++
            }
            return count
        }
        if (colourful(whole) == 0) {
            // Nothing of the window (not even its controls) is capturable: verify mpv's side instead.
            val scale = java.awt.Toolkit.getDefaultToolkit().screenResolution / 96.0
            val log = reports.resolve("video-mpv.log")
            waitUntil("mpv configured its window") {
                Files.isRegularFile(log) && Regex("""\[vo/gpu] Window size: (\d+)x(\d+)""").findAll(Files.readString(log))
                    .lastOrNull()?.let { match ->
                        val (width, height) = match.destructured
                        kotlin.math.abs(width.toInt() - area.width * scale) <= 3 && kotlin.math.abs(height.toInt() - area.height * scale) <= 3
                    } == true
            }
            println("VIDEO_SURFACE screen capture unavailable; mpv output configured for ${area.width}x${area.height} @${scale}x")
            return
        }
        var surface = 0
        waitUntil("video pixels", 15_000) {
            val image = Robot().createScreenCapture(area)
            ImageIO.write(image, "png", reports.resolve("video-surface.png").toFile())
            surface = 0
            for (x in 0 until image.width step 8) for (y in 0 until image.height step 8) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 60) surface++
            }
            surface > 50
        }
        println("VIDEO_SURFACE ${area.width}x${area.height} colourful=$surface")
    }
    private fun canvases(container: Container): List<Canvas> = container.components.flatMap { child ->
        when (child) {
            is Canvas -> if (child.javaClass.name.contains("VideoCanvas")) listOf(child) else emptyList()
            is Container -> canvases(child)
            else -> emptyList()
        }
    }

    private fun waitUntil(what: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $what" }
            Thread.sleep(100)
        }
    }
}

/** A minimal UPnP renderer: answers M-SEARCH, describes itself, takes SOAP actions and fetches what it is told to play. */
private class FakeRenderer : AutoCloseable {
    val actions = java.util.concurrent.CopyOnWriteArrayList<String>()
    val fetched = java.util.concurrent.atomic.AtomicLong()
    val fetchStatus = java.util.concurrent.atomic.AtomicInteger()
    private val loopback = java.net.InetAddress.getLoopbackAddress()
    private val http = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    private val udp = java.net.DatagramSocket(InetSocketAddress(loopback, 0))
    val port get() = udp.localPort

    init {
        http.createContext("/d.xml") { exchange ->
            val xml = ("<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>测试电视</friendlyName>" +
                "<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service>" +
                "</serviceList></device></root>").toByteArray()
            exchange.sendResponseHeaders(200, xml.size.toLong()); exchange.responseBody.use { it.write(xml) }
        }
        http.createContext("/av") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPAction").substringAfter('#').trim('"')
            val body = exchange.requestBody.readAllBytes().decodeToString()
            actions += action
            if (action == "SetAVTransportURI") {
                val uri = Regex("<CurrentURI>([^<]+)</CurrentURI>").find(body)!!.groupValues[1].replace("&amp;", "&")
                Thread({
                    val response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI(uri)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray())
                    fetchStatus.set(response.statusCode()); fetched.set(response.body().size.toLong())
                }).start()
            }
            val reply = when (action) {
                "GetPositionInfo" -> "<RelTime>0:00:12</RelTime><TrackDuration>0:00:40</TrackDuration>"
                "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                else -> ""
            }
            val xml = "<s:Envelope><s:Body><u:${action}Response>$reply</u:${action}Response></s:Body></s:Envelope>".toByteArray()
            exchange.sendResponseHeaders(200, xml.size.toLong()); exchange.responseBody.use { it.write(xml) }
        }
        http.start()
        Thread({
            val buffer = ByteArray(2048)
            while (!udp.isClosed) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                runCatching { udp.receive(packet) }.getOrNull() ?: break
                val reply = "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:${http.address.port}/d.xml\r\n\r\n".toByteArray()
                udp.send(java.net.DatagramPacket(reply, reply.size, packet.socketAddress))
            }
        }).apply { isDaemon = true; start() }
    }

    override fun close() { udp.close(); http.stop(0) }
}
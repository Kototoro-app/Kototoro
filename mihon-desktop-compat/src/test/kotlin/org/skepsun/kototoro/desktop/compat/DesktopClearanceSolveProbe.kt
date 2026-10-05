package org.skepsun.kototoro.desktop.compat

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Mihon's default solver against a real WebView2: a loopback site answers like a Cloudflare managed challenge
 * (403, `Server: cloudflare`, `#challenge-running`, a script that writes `cf_clearance` and reloads). The SDK client
 * must come back with the content without any manual browser prompt.
 */
internal object DesktopClearanceSolveProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try { runBlocking {
            val root = Path.of(args[1])
            FileSourcePreferenceStore(root.resolve("preferences")).use { preferences ->
                val platform = MihonDesktopPlatform(root.resolve("compat"), File(args[2]), enableBrowserChallenges = true)
                platform.initialize(preferences)
                try {
                    val challenges = checkNotNull(platform.browserChallenges)
                    ChallengeSite(interactive = args[0] == "interactive").use { site ->
                        var prompted = false
                        val watcher = launch(Dispatchers.Default) {
                            while (true) {
                                challenges.pending.value?.let { prompt ->
                                    prompted = true
                                    // Declining the manual prompt finishes an interactive run quickly.
                                    challenges.complete(prompt.id, false)
                                }
                                delay(20)
                            }
                        }
                        val result = async(Dispatchers.IO) {
                            runCatching {
                                platform.sharedHttpClient().newCall(Request.Builder().url(site.url("/protected")).build())
                                    .execute().use { it.code to it.body.string() }
                            }
                        }.await()
                        watcher.cancel()
                        when (args[0]) {
                            "managed" -> {
                                val (code, body) = result.getOrThrow()
                                check(code == 200 && body.contains("protected content")) { "$code $body" }
                                check(!prompted) { "A managed challenge must not ask the user" }
                                check(site.challenges.get() >= 1) { "The site never served its challenge" }
                            }
                            "interactive" -> {
                                // The hidden solve gives up after the grace period and hands over to the user.
                                check(result.isFailure) { "An interactive challenge cannot pass unattended: $result" }
                                check(prompted) { "An unsolvable challenge must fall back to the manual prompt" }
                            }
                        }
                    }
                } finally { platform.close() }
            }
            awaitBrowserProfileRelease(root)
            println("CLEARANCE_SOLVE_OK=${args[0]}")
        } } catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
    }

}


private class ChallengeSite(private val interactive: Boolean) : AutoCloseable {
    val challenges = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@ChallengeSite.executor
        createContext("/") { exchange ->
            try {
                val cookies = exchange.requestHeaders["Cookie"].orEmpty().joinToString(";")
                val cleared = cookies.contains("cf_clearance=solved")
                val (status, html) = when {
                    exchange.requestURI.path != "/protected" -> 404 to "<html><body>missing</body></html>"
                    cleared -> 200 to "<html><head><title>site</title></head><body><p>protected content</p></body></html>"
                    interactive -> {
                        challenges.incrementAndGet()
                        403 to """<html><head><title>Just a moment...</title></head><body>
                            <div id="challenge-stage"></div><input type="hidden" name="cf-turnstile-response">
                            </body></html>"""
                    }
                    else -> {
                        challenges.incrementAndGet()
                        403 to """<html><head><title>Just a moment...</title></head><body>
                            <div id="challenge-running"></div>
                            <script>setTimeout(function () {
                              document.cookie = 'cf_clearance=solved; path=/';
                              location.reload();
                            }, 700);</script></body></html>"""
                    }
                }
                val bytes = html.toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                if (status == 403) exchange.responseHeaders.set("Server", "cloudflare")
                // Real challenge pages carry the explicit marker; the managed fixture relies on Mihon's 403 rule alone.
                if (status == 403 && interactive) exchange.responseHeaders.set("cf-mitigated", "challenge")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) { /* cancelled request */ }
            finally { exchange.close() }
        }
        start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    override fun close() { server.stop(0); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
}

package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.desktop.compat.DesktopWebViewBridge
import org.skepsun.kototoro.desktop.compat.DesktopBrowserCookies
import org.skepsun.kototoro.desktop.compat.DesktopBrowserWindow
import org.skepsun.kototoro.desktop.compat.DesktopWebDocument
import java.io.Closeable
import java.io.File
import java.nio.file.Path

/** Session owns one debug bridge; UI recomposition and cancellation cannot orphan its native process. */
internal class DesktopBrowser(
    private val executable: File,
    private val profile: Path,
    private val cookieFactory: (DesktopWebViewBridge) -> DesktopBrowserCookies,
) : Closeable {
    private val gate = Mutex()
    internal val isBusy: Boolean get() = gate.isLocked
    private var bridge: DesktopWebViewBridge? = null
    private var initialized = false
    private var cookies: DesktopBrowserCookies? = null
    private var closed = false

    suspend fun evaluate(html: String, script: String): String = operation { engine, sharedCookies ->
        sharedCookies.push()
        check(engine.loadHtml(html)) { "HTML load failed" }
        engine.evaluateJs(script).also { sharedCookies.pull() }
    }

    suspend fun openUrl(url: String): DesktopWebDocument = operation { engine, sharedCookies ->
        val address = java.net.URI(url)
        require(address.scheme in listOf("http", "https") && !address.host.isNullOrBlank()) {
            "请输入有效的 HTTP 或 HTTPS 网页地址"
        }
        sharedCookies.push()
        engine.setWindowVisible(true)
        check(engine.navigate(url)) { "网页加载失败" }
        sharedCookies.pull()
        engine.document()
    }

    suspend fun evaluateCurrent(script: String): String = operation { engine, sharedCookies ->
        sharedCookies.push()
        engine.evaluateJs(script).also { sharedCookies.pull() }
    }

    suspend fun showWindow(): DesktopBrowserWindow = operation { engine, _ -> engine.setWindowVisible(true) }

    suspend fun hideWindow(): DesktopBrowserWindow = operation { engine, _ -> engine.setWindowVisible(false) }

    suspend fun windowState(): DesktopBrowserWindow = operation { engine, _ -> engine.windowState() }

    suspend fun syncCookies(): Unit = operation { _, sharedCookies ->
        sharedCookies.push()
        sharedCookies.pull()
    }

    private suspend fun <T> operation(
        block: suspend (DesktopWebViewBridge, DesktopBrowserCookies) -> T,
    ): T = gate.withLock {
        withContext(Dispatchers.IO) {
            val engine = synchronized(this@DesktopBrowser) {
                check(!closed) { "Browser is closed" }
                bridge ?: DesktopWebViewBridge(executable, profile).also { bridge = it }
            }
            try {
                if (!initialized) {
                    engine.start()
                    initialized = true
                }
                val sharedCookies = cookies ?: cookieFactory(engine).also { cookies = it }
                block(engine, sharedCookies)
            } catch (error: Throwable) {
                // Cancellation stops navigation in the bridge; a live, initialized window stays session-owned.
                if (!initialized || !engine.isRunning) {
                    synchronized(this@DesktopBrowser) {
                        try { engine.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                        bridge = null
                        initialized = false
                        cookies = null
                    }
                }
                throw error
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        bridge?.close()
        bridge = null
    }
}

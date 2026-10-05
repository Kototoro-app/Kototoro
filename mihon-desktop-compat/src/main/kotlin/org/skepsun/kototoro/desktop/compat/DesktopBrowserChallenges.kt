package org.skepsun.kototoro.desktop.compat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Request
import org.skepsun.kototoro.core.network.cloudflare.CLEARANCE_COOKIE_NAMES
import org.skepsun.kototoro.core.network.cloudflare.CLEARANCE_SOLVE_TIMEOUT_MS
import org.skepsun.kototoro.core.network.cloudflare.ClearanceSolveDecision
import org.skepsun.kototoro.core.network.cloudflare.ClearanceSolveTracker
import org.skepsun.kototoro.core.network.cloudflare.ClearanceSolver
import org.skepsun.kototoro.core.network.cloudflare.safeForBrowser
import org.skepsun.kototoro.core.network.webview.CF_STATE_JS
import org.skepsun.kototoro.core.network.webview.parseCloudFlarePageState
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

data class DesktopBrowserChallenge(val id: Long, val url: String)

/**
 * One session-owned browser at a time: Mihon's automatic off-screen solve ([solve], shared rules in core-cloudflare)
 * and, when that cannot pass, a human interaction. Source calls retain cancellation and retry ownership.
 */
class DesktopBrowserChallenges internal constructor(
    private val executable: File,
    private val profile: Path,
    private val cookieFactory: (DesktopWebViewBridge) -> DesktopBrowserCookies,
    private val timeoutMs: Long = 120_000,
    private val solveTimeoutMs: Long = CLEARANCE_SOLVE_TIMEOUT_MS,
    private val pollMs: Long = 500,
) : Closeable, ClearanceSolver {
    private val gate = Mutex()
    private val ids = AtomicLong()
    private val requestOwner = ThreadLocal<Job?>()
    private val mutablePending = MutableStateFlow<DesktopBrowserChallenge?>(null)
    val pending = mutablePending.asStateFlow()
    private var completion: CompletableDeferred<Boolean>? = null
    private var browser: DesktopWebViewBridge? = null
    private var closed = false

    /** Completing an old UI prompt cannot accept a later request. */
    @Synchronized
    fun complete(id: Long, accepted: Boolean) {
        if (mutablePending.value?.id == id) completion?.complete(accepted)
    }

    /** Retains the actual request's coroutine owner across SDK calls that synchronously subscribe to Rx. */
    suspend fun <T> withRequestCancellation(block: suspend () -> T): T =
        withContext(requestOwner.asContextElement(currentCoroutineContext()[Job])) { block() }

    internal fun isRequestCancelled(): Boolean = requestOwner.get()?.isCancelled == true

    suspend fun showWindow(id: Long): DesktopBrowserWindow? = withContext(Dispatchers.IO) {
        val engine = synchronized(this@DesktopBrowserChallenges) {
            browser.takeIf { !closed && mutablePending.value?.id == id }
        }
        engine?.setWindowVisible(true)
    }

    internal fun resolve(request: Request, call: Call) {
        val owner = requestOwner.get()
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(timeoutMs) {
                    coroutineScope {
                        val signal = CompletableDeferred<Boolean>()
                        val watcher = launch {
                            while (true) {
                                if (call.isCanceled() || owner?.isCancelled == true) throw IOException("网页验证请求已取消")
                                if (signal.isCompleted && !signal.await()) throw IOException("网页验证已取消")
                                delay(100)
                            }
                        }
                        try {
                            gate.withLock { interact(request, call, signal) }
                        } finally { watcher.cancel() }
                    }
                }
            }
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("网页验证未完成：${error.message ?: error.javaClass.simpleName}", error) }
    }

    /**
     * Mihon's default solver on WebView2: load the request in the hidden browser with the SDK's cookies (minus the old
     * clearance), poll until a new `cf_clearance` appears and copy it back to the SDK store. Returns false on timeout,
     * an interactive or blocking page, or no challenge at all, so the caller can ask the user instead.
     */
    override suspend fun solve(request: Request): Boolean = withContext(Dispatchers.IO) {
        // Cancellation reaches here as coroutine cancellation: the caller stops waiting and the shared coordinator
        // cancels the solve, which closes the browser below.
        gate.withLock {
            val engine = synchronized(this@DesktopBrowserChallenges) {
                if (closed) return@withLock false
                DesktopWebViewBridge(executable, profile, request.header("User-Agent"))
            }
            try {
                withTimeoutOrNull(solveTimeoutMs) {
                    engine.start()
                    val cookies = cookieFactory(engine)
                    cookies.push()
                    val url = request.url.toString()
                    suspend fun clearance() =
                        engine.getCookies(url).firstOrNull { it.name in CLEARANCE_COOKIE_NAMES }
                    // Mihon removes the old clearance first, so a freshly written cookie is the success signal.
                    val old = clearance()
                    engine.getCookies(url).filter { it.name in CLEARANCE_COOKIE_NAMES && it.domain != null }
                        .forEach { engine.deleteCookie(it.name, it.domain!!, it.path ?: "/") }
                    val tracker = ClearanceSolveTracker(url)
                    // The challenge page loads with an HTTP error status; WebView2 still renders it.
                    try {
                        engine.navigate(url, timeoutMs = solveTimeoutMs, headers = request.headers.safeForBrowser(),
                            allowHttpErrorResponse = true)
                    } catch (_: TimeoutCancellationException) {
                        // A challenge that keeps redirecting may never report completion; the polls decide.
                    }
                    while (true) {
                        val current = clearance()
                        val hasNew = current != null && current.value != old?.value
                        val state = parseCloudFlarePageState(runCatching { engine.evaluateJs(CF_STATE_JS) }.getOrNull())
                        when (tracker.onPageState(state, hasNew, System.currentTimeMillis())) {
                            ClearanceSolveDecision.SOLVED -> {
                                cookies.pull()
                                return@withTimeoutOrNull true
                            }
                            ClearanceSolveDecision.FAILED -> return@withTimeoutOrNull false
                            ClearanceSolveDecision.WAIT -> delay(pollMs)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
            } finally {
                engine.close()
            }
        }
    }

    private suspend fun interact(request: Request, call: Call, signal: CompletableDeferred<Boolean>) {
        if (call.isCanceled()) throw IOException("网页验证请求已取消")
        val engine = synchronized(this) {
            check(!closed) { "Browser interaction is closed" }
            DesktopWebViewBridge(executable, profile, request.header("User-Agent")).also {
                browser = it
                completion = signal
            }
        }
        try {
            engine.start()
            val cookies = cookieFactory(engine)
            cookies.push()
            engine.setWindowVisible(true)
            synchronized(this) {
                check(!closed) { "Browser interaction is closed" }
                mutablePending.value = DesktopBrowserChallenge(ids.incrementAndGet(), request.url.toString())
            }
            val headers = request.headers.toMap().filterKeys { !it.equals("Cookie", ignoreCase = true) }
            check(engine.navigate(request.url.toString(), headers = headers, allowHttpErrorResponse = true)) {
                "验证页面加载失败"
            }
            if (!signal.await()) throw IOException("网页验证已取消")
            if (call.isCanceled()) throw IOException("网页验证请求已取消")
            cookies.pull()
        } finally {
            synchronized(this) {
                mutablePending.value = null
                completion = null
                browser = null
                engine.close()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        completion?.complete(false)
        browser?.close()
    }
}

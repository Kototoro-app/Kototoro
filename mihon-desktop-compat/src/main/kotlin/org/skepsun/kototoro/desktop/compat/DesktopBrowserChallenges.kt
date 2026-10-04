package org.skepsun.kototoro.desktop.compat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okhttp3.Call
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

data class DesktopBrowserChallenge(val id: Long, val url: String)

/** One session-owned human interaction at a time; source calls retain cancellation and retry ownership. */
class DesktopBrowserChallenges internal constructor(
    private val executable: File,
    private val profile: Path,
    private val cookieFactory: (DesktopWebViewBridge) -> DesktopBrowserCookies,
    private val timeoutMs: Long = 120_000,
) : Closeable {
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

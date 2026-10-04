package org.skepsun.kototoro.desktop.compat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Base64

@Serializable
data class DesktopWebCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val isHttpOnly: Boolean = false,
    val isSecure: Boolean = false,
    val expires: String? = null,
    val isSession: Boolean = true,
    val expiresEpochMillis: Long? = null,
)

/**
 * Communicates with the external WebView2 bridge process over standard I/O using JSON-RPC.
 * Process isolation ensures Chromium stability issues or crashes never corrupt the host JVM.
 */
class DesktopWebViewBridge(
    private val bridgeExecutable: File,
    private val userDataDir: Path? = null,
    private val userAgent: String? = null,
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val closed = AtomicBoolean(false)
    private val outputClosed = AtomicBoolean(false)
    private val navigationGate = Mutex()
    internal val pendingRequestCount: Int get() = pendingRequests.size
    internal val processExitCode: Int? get() = if (process.isAlive) null else process.exitValue()
    val isRunning: Boolean get() = !closed.get() && !outputClosed.get() && process.isAlive

    private val process: Process
    private val writer: BufferedWriter
    private val readerThread: Thread

    init {
        require(bridgeExecutable.isFile) { "Bridge executable not found: ${bridgeExecutable.absolutePath}" }
        val pb = ProcessBuilder(bridgeExecutable.absolutePath)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        process = pb.start()
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                var line: String? = null
                while (!closed.get() && reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.isEmpty()) continue
                    try {
                        val obj = json.parseToJsonElement(trimmed).jsonObject
                        val id = obj["id"]?.jsonPrimitive?.longOrNull
                        if (id != null) {
                            val deferred = pendingRequests.remove(id)
                            deferred?.complete(obj)
                        }
                    } catch (parseError: Throwable) {
                        // ignore malformed diagnostic line
                    }
                }
            } catch (ioError: Throwable) {
                // stream closed
            } finally {
                synchronized(writer) {
                    outputClosed.set(true)
                    failPending(IllegalStateException("WebView bridge process output closed"))
                }
            }
        }, "Kototoro-WebView2-Bridge-Reader").apply { isDaemon = true }
        readerThread.start()
    }

    suspend fun start(timeoutMs: Long = 15000): String = withTimeout(timeoutMs) {
        val params = buildJsonObject {
            userDataDir?.let { put("userDataDir", it.toAbsolutePath().normalize().toString()) }
            userAgent?.let { put("userAgent", it) }
        }
        val res = sendCommand("init", params)
        val browserVersion = res["browserVersion"]?.jsonPrimitive?.content ?: "unknown"
        browserVersion
    }

    suspend fun loadHtml(html: String, timeoutMs: Long = 15000, baseUrl: String? = null,
        mimeType: String = "text/html"): Boolean = navigation(timeoutMs) {
        val res = sendCommand("loadHtml", buildJsonObject {
            put("html", html); baseUrl?.let { put("baseUrl", it) }; put("mimeType", mimeType)
        })
        res["status"]?.jsonPrimitive?.content == "ok"
    }

    suspend fun navigate(url: String, timeoutMs: Long = 15000, headers: Map<String, String> = emptyMap(),
        postData: ByteArray? = null, allowHttpErrorResponse: Boolean = false): Boolean = navigation(timeoutMs) {
        val res = sendCommand("navigate", buildJsonObject {
            put("url", url)
            put("httpMethod", if (postData == null) "GET" else "POST")
            put("allowHttpErrorResponse", allowHttpErrorResponse)
            put("headers", buildJsonObject { headers.forEach { (key, value) -> put(key, value) } })
            postData?.let { put("bodyBase64", Base64.getEncoder().encodeToString(it)) }
        })
        res["status"]?.jsonPrimitive?.content == "ok"
    }

    private suspend fun <T> navigation(timeoutMs: Long, block: suspend () -> T): T = navigationGate.withLock {
        try { withTimeout(timeoutMs) { block() } } catch (error: CancellationException) {
            withContext(NonCancellable) { runCatching { stopLoading(1000) } }
            throw error
        }
    }

    suspend fun stopLoading(timeoutMs: Long = 1000) = withTimeout(timeoutMs) { sendCommand("stop"); Unit }

    suspend fun document(timeoutMs: Long = 5000): DesktopWebDocument = withTimeout(timeoutMs) {
        val result = sendCommand("document")
        DesktopWebDocument(result["url"]?.jsonPrimitive?.content.orEmpty(), result["title"]?.jsonPrimitive?.content.orEmpty())
    }

    suspend fun setWindowVisible(visible: Boolean, width: Int? = null, height: Int? = null): DesktopBrowserWindow =
        withTimeout(5000) {
            window(sendCommand("visibility", buildJsonObject {
                put("visible", visible)
                width?.let { put("width", it) }; height?.let { put("height", it) }
            }))
        }

    suspend fun windowState(): DesktopBrowserWindow = withTimeout(5000) { window(sendCommand("window")) }

    /** Uses the same FormClosing path as the native window's close button; the session remains its owner. */
    suspend fun dismissWindow(): DesktopBrowserWindow = withTimeout(5000) { window(sendCommand("dismissWindow")) }

    suspend fun capturePreview(): ByteArray = withTimeout(5000) {
        Base64.getDecoder().decode(sendCommand("capturePreview")["pngBase64"]!!.jsonPrimitive.content)
    }

    private fun window(value: JsonObject) = DesktopBrowserWindow(
        visible = value["visible"]?.jsonPrimitive?.booleanOrNull == true,
        width = value["width"]?.jsonPrimitive?.intOrNull ?: 0,
        height = value["height"]?.jsonPrimitive?.intOrNull ?: 0,
        title = value["title"]?.jsonPrimitive?.content.orEmpty(),
    )

    suspend fun applySettings(javaScript: Boolean, userAgent: String?) {
        withTimeout(5000) { sendCommand("settings", buildJsonObject {
            put("javaScript", javaScript); userAgent?.let { put("userAgent", it) }
        }) }
    }

    suspend fun evaluateJs(script: String, timeoutMs: Long = 10000): String = withTimeout(timeoutMs) {
        val res = sendCommand("evaluateJs", buildJsonObject { put("script", script) })
        res["value"]?.jsonPrimitive?.content ?: "null"
    }

    suspend fun getCookies(url: String, timeoutMs: Long = 5000): List<DesktopWebCookie> = withTimeout(timeoutMs) {
        val res = sendCommand("getCookies", buildJsonObject { put("url", url) })
        val cookiesArray = res["cookies"]?.jsonArray ?: return@withTimeout emptyList()
        cookiesArray.map { elem ->
            val obj = elem.jsonObject
            DesktopWebCookie(
                name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                value = obj["value"]?.jsonPrimitive?.content.orEmpty(),
                domain = obj["domain"]?.jsonPrimitive?.content,
                path = obj["path"]?.jsonPrimitive?.content,
                isHttpOnly = obj["isHttpOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                isSecure = obj["isSecure"]?.jsonPrimitive?.booleanOrNull ?: false,
                expires = obj["expires"]?.jsonPrimitive?.content,
                isSession = obj["isSession"]?.jsonPrimitive?.booleanOrNull ?: true,
                expiresEpochMillis = obj["expiresEpochMillis"]?.jsonPrimitive?.longOrNull,
            )
        }
    }

    suspend fun setCookie(
        url: String,
        name: String,
        value: String,
        domain: String? = null,
        path: String? = "/",
        timeoutMs: Long = 5000,
        isHttpOnly: Boolean = false,
        isSecure: Boolean = false,
        expiresEpochMillis: Long? = null,
    ): Boolean = withTimeout(timeoutMs) {
        val params = buildJsonObject {
            put("url", url)
            put("name", name)
            put("value", value)
            domain?.let { put("domain", it) }
            path?.let { put("path", it) }
            put("isHttpOnly", isHttpOnly); put("isSecure", isSecure)
            expiresEpochMillis?.let { put("expiresEpochMillis", it) }
        }
        val res = sendCommand("setCookie", params)
        res["status"]?.jsonPrimitive?.content == "ok"
    }

    suspend fun deleteCookie(name: String, domain: String, path: String) {
        withTimeout(5000) { sendCommand("deleteCookie", buildJsonObject {
            put("name", name); put("domain", domain); put("path", path)
        }) }
    }

    private suspend fun sendCommand(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        check(!closed.get()) { "WebView bridge is closed" }
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()

        val reqObj = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val payload = reqObj.toString()

        try {
            withContext(Dispatchers.IO) {
                synchronized(writer) {
                    check(!closed.get() && !outputClosed.get()) { "WebView bridge is closed" }
                    pendingRequests[id] = deferred
                    writer.write(payload)
                    writer.newLine()
                    writer.flush()
                }
            }
            val resp = deferred.await()
            val error = resp["error"]?.jsonObject
            if (error != null) {
                val msg = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
                throw IllegalStateException("WebView2 Bridge error in $method: $msg")
            }
            return resp["result"]?.jsonObject ?: JsonObject(emptyMap())
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun failPending(cause: Throwable) {
        val iterator = pendingRequests.values.iterator()
        while (iterator.hasNext()) {
            val deferred = iterator.next()
            iterator.remove()
            deferred.completeExceptionally(cause)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val id = requestId.incrementAndGet()
            val reqObj = buildJsonObject {
                put("id", id)
                put("method", "close")
            }
            synchronized(writer) {
                writer.write(reqObj.toString())
                writer.newLine()
                writer.flush()
            }
        } catch (_: Throwable) {
            // Process may already be dead
        }
        try {
            writer.close()
        } catch (_: Throwable) {}

        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(3, TimeUnit.SECONDS)) { "WebView bridge did not terminate" }
            }
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while closing WebView bridge", error)
        } finally {
            failPending(IllegalStateException("WebView bridge closed"))
            process.inputStream.close()
            if (Thread.currentThread() !== readerThread && !Thread.currentThread().isInterrupted) readerThread.join(1000)
        }
    }
}

data class DesktopWebDocument(val url: String, val title: String)

data class DesktopBrowserWindow(val visible: Boolean, val width: Int, val height: Int, val title: String)

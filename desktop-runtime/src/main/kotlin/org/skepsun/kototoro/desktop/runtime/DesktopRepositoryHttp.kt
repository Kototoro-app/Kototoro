@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.*
import java.io.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resumeWithException

internal class RepositoryHttpException(val status: Int) : IOException("仓库请求失败：HTTP $status")

/** Owned HTTP responses; cancellation closes a streaming body as well as cancelling the header future. */
internal class DesktopRepositoryHttp : Closeable {
    private val closed = AtomicBoolean()
    private var client: HttpClient? = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val bodies = ConcurrentHashMap.newKeySet<InputStream>()

    suspend fun <T> read(uri: URI, limit: Long, consume: suspend (URI, InputStream) -> T): T = withTimeout(30_000) {
        readResponse(uri, limit, consume)
    }

    private suspend fun <T> readResponse(
        uri: URI,
        limit: Long,
        consume: suspend (URI, InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        check(!closed.get()) { "仓库连接已经关闭" }
        val request = HttpRequest.newBuilder(repositoryUri(uri.toString())).timeout(Duration.ofSeconds(30))
            .header("Accept-Encoding", "gzip").GET().build()
        val future = requireNotNull(client).sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        pending += future
        if (closed.get()) future.cancel(true)
        val response = try {
            suspendCancellableCoroutine<HttpResponse<InputStream>> { continuation ->
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error != null) continuation.resumeWithException(error.cause ?: error)
                    else continuation.resume(value) { _, lost, _ -> lost.body().close() }
                }
            }
        } finally { pending -= future }
        val body = response.body()
        bodies += body
        val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) runCatching { body.close() }
        }
        try {
            check(!closed.get()) { "仓库连接已经关闭" }
            currentCoroutineContext().ensureActive()
            require(response.uri().scheme == uri.scheme || response.uri().scheme == "https") { "拒绝不安全的仓库重定向" }
            if (response.statusCode() !in 200..299) throw RepositoryHttpException(response.statusCode())
            response.headers().firstValueAsLong("Content-Length").ifPresent {
                require(it <= limit) { "仓库数据超过大小限制" }
            }
            val input = PushbackInputStream(LimitedRepositoryInput(body, limit), 2)
            val prefix = ByteArray(2)
            val first = input.readNBytes(prefix, 0, 2)
            if (first > 0) input.unread(prefix, 0, first)
            val decoded = if (first == 2 && prefix[0] == 0x1f.toByte() && prefix[1] == 0x8b.toByte()) {
                GZIPInputStream(input)
            } else input
            LimitedRepositoryInput(decoded, limit).use { consume(response.uri(), it) }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally { cancellation.dispose(); bodies -= body; body.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.forEach { it.cancel(true) }
        bodies.forEach { runCatching { it.close() } }
        client = null
    }
}

internal fun repositoryUri(value: String): URI {
    val uri = URI(value.trim()).normalize()
    require(uri.isAbsolute && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null) { "请输入 HTTP(S) 仓库地址，不包含账号或片段" }
    return uri
}

private class LimitedRepositoryInput(private val input: InputStream, private val limit: Long) : InputStream() {
    private var count = 0L
    override fun read(): Int = input.read().also {
        if (it >= 0) { count++; require(count <= limit) { "仓库数据超过大小限制" } }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length).also {
        if (it > 0) { count += it; require(count <= limit) { "仓库数据超过大小限制" } }
    }
    override fun close() = input.close()
}

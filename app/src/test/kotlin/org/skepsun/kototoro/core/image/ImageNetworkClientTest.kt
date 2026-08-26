package org.skepsun.kototoro.core.image

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coil 网络图片调用（封面/图标的非 Mihon 路径）每请求超时上限的回归测试。
 *
 * 背景（P2 真机验证）：Coil 共享全局 OkHttp 客户端（callTimeout 300s）。弱网/VPN
 * 黑洞时一个封面请求可以长时间占住图片并发槽位（放大发热与卡顿）。真机测量正常封面
 * 0.5–2.6 秒，因此按场景收紧到 20s 上限（[IMAGE_NETWORK_CALL_TIMEOUT_MS]），让失败
 * 的请求快速释放并发槽位。
 */
class ImageNetworkClientTest {

    @Test
    fun `slow response is cancelled at the image network call timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeadersDelay(5, TimeUnit.SECONDS))
            val client = buildImageNetworkClient(OkHttpClient(), callTimeoutMillis = 300)

            val startedAt = System.currentTimeMillis()
            val error = runCatching {
                client.newCall(Request.Builder().url(server.url("/slow.jpg")).build()).execute()
            }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue(
                error is InterruptedIOException || error is SocketTimeoutException,
                "expected a timeout error but was: $error",
            )
            assertTrue(elapsed < 3_000, "timeout took too long: $elapsed ms")
        }
    }

    @Test
    fun `default image network call timeout is 20 seconds`() {
        val client = buildImageNetworkClient(OkHttpClient())

        assertEquals(20000L, client.callTimeoutMillis.toLong())
    }

    @Test
    fun `fast response is unaffected by the timeout cap`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client = buildImageNetworkClient(OkHttpClient())

            val response = client.newCall(Request.Builder().url(server.url("/fast.jpg")).build()).execute()

            response.use {
                assertEquals(200, it.code)
                assertEquals("ok", it.body?.string())
            }
        }
    }
}

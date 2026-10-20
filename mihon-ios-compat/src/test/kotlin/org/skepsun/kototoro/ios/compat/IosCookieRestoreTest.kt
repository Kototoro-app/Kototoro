package org.skepsun.kototoro.ios.compat

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

class IosCookieRestoreTest {
    @Test
    fun `a saved login cookie of a dotted domain is still there after a restart`(@TempDir dir: Path) {
        val url = "https://n.novelia.cc/".toHttpUrl()
        fun <T> launch(block: (OkHttpClient) -> T): T {
            val store = FileSourcePreferenceStore(dir.resolve("preferences"))
            val platform = MihonIosPlatform()
            try {
                platform.initialize(store)
                val client = MihonIosPlatform::class.java.getDeclaredField("client")
                    .apply { isAccessible = true }.get(platform) as OkHttpClient
                return block(client)
            } finally { platform.close(); store.close() }
        }
        launch { client ->
            val cookie = Cookie.parse(url, "authorization=jwt")!!.newBuilder()
                .expiresAt(System.currentTimeMillis() + 86_400_000L).build()
            client.cookieJar.saveFromResponse(url, listOf(cookie))
        }
        val restored = launch { client -> client.cookieJar.loadForRequest(url) }
        assertEquals(listOf("authorization" to "jwt"), restored.map { it.name to it.value })
    }
}

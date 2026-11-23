package org.skepsun.kototoro.core.network

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommonHeadersSourceNameTest {

    @Test
    fun `ascii names pass through unchanged`() {
        assertEquals("MIHON_123", CommonHeaders.encodeSourceName("MIHON_123"))
    }

    @Test
    fun `non-ascii names are accepted by OkHttp and round trip`() {
        val name = "CLOUDSTREAM_AnimeRift_أنمي رِفت"
        val encoded = CommonHeaders.encodeSourceName(name)
        val headers = Headers.Builder().add(CommonHeaders.MANGA_SOURCE, encoded).build()
        assertEquals(name, CommonHeaders.decodeSourceName(headers[CommonHeaders.MANGA_SOURCE]!!))
    }

    @Test
    fun `percent sign round trips`() {
        val name = "JS_100%_源"
        assertEquals(name, CommonHeaders.decodeSourceName(CommonHeaders.encodeSourceName(name)))
    }
}

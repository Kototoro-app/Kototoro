package org.skepsun.kototoro.video.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VideoLocalCacheProxyTest {

    @Test
    fun `sanitizes junk before a valid HLS manifest`() {
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts",
            sanitizeHlsManifest("garbage-prefix\u0000\n#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts"),
        )
        assertNull(sanitizeHlsManifest("<html>upstream error</html>"))
        assertNull(sanitizeHlsManifest("error mentioning #EXTM3U without playlist tags"))
    }

    @Test
    fun `dynamic source key ignores endpoint subpath`() {
        assertEquals("abc123", dynamicSourceKey("/dynamic/abc123/proxy"))
        assertEquals("abc123", dynamicSourceKey("/dynamic/abc123"))
        assertEquals(null, dynamicSourceKey("/video/abc123"))
    }

    @Test
    fun `rewrites media lines and every HLS URI attribute`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="audio/index.m3u8"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,URI="iframe.m3u8"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="keys/session.key"
            #EXT-X-KEY:METHOD=AES-128,URI="keys/media.key"
            #EXT-X-MAP:URI="init.mp4"
            segment-001.ts?sig=abc
        """.trimIndent()

        val rewritten = rewriteHlsPlaylistUris(playlist) { uri -> "proxy/$uri" }

        assertEquals(
            """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,URI="proxy/audio/index.m3u8"
                #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,URI="proxy/iframe.m3u8"
                #EXT-X-SESSION-KEY:METHOD=AES-128,URI="proxy/keys/session.key"
                #EXT-X-KEY:METHOD=AES-128,URI="proxy/keys/media.key"
                #EXT-X-MAP:URI="proxy/init.mp4"
                proxy/segment-001.ts?sig=abc
            """.trimIndent(),
            rewritten,
        )
    }
}

package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AniyomiModelAbiCompatibilityTest {

    @Test
    fun `extension lib 14 and 16 model constructors remain available`() {
        assertTrue(Hoster::class.java.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java, List::class.java, String::class.java),
            )
        })
        assertTrue(Hoster::class.java.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(
                    String::class.java,
                    String::class.java,
                    List::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ),
            )
        })
        assertTrue(Video::class.java.constructors.any { constructor ->
            constructor.parameterCount == 6 && constructor.parameterTypes[3] == Headers::class.java
        })
        assertTrue(Video::class.java.constructors.any { constructor ->
            constructor.parameterCount == 14 && constructor.parameterTypes[4] == Headers::class.java
        })
    }

    @Suppress("DEPRECATION")
    @Test
    fun `extension lib 14 video copy remains available`() {
        val original = Video(
            url = "https://example.org/page",
            quality = "720p",
            videoUrl = "https://example.org/video.m3u8",
            subtitleTracks = listOf(Track("https://example.org/sub.vtt", "en")),
        ).copy(initialized = true)

        val copied = original.copy(quality = "Fansub 720p")

        assertEquals("Fansub 720p", copied.quality)
        assertEquals(original.videoUrl, copied.videoUrl)
        assertEquals(original.subtitleTracks, copied.subtitleTracks)
        assertTrue(copied.initialized)
        assertTrue(Video::class.java.declaredMethods.any { method ->
            method.name == "copy\$default" && method.parameterCount == 9
        })
    }
}

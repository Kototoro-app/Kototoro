package org.skepsun.kototoro.video.player

import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleMimeTypeResolverTest {
    @Test
    fun `explicit mime type wins over url extension`() {
        assertEquals(
            MimeTypes.TEXT_VTT,
            SubtitleMimeTypeResolver.resolve(MimeTypes.TEXT_VTT, "https://example/subtitle.srt"),
        )
    }

    @Test
    fun `common subtitle extensions are detected with query parameters`() {
        assertEquals(MimeTypes.TEXT_VTT, SubtitleMimeTypeResolver.resolve(null, "https://example/a.VTT?token=1"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SubtitleMimeTypeResolver.resolve(null, "https://example/a.srt"))
        assertEquals(MimeTypes.TEXT_SSA, SubtitleMimeTypeResolver.resolve(null, "https://example/a.ass#fragment"))
        assertEquals(MimeTypes.APPLICATION_TTML, SubtitleMimeTypeResolver.resolve(null, "https://example/a.xml"))
    }

    @Test
    fun `extensionless url uses subrip as final fallback`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            SubtitleMimeTypeResolver.resolve(null, "https://example/subtitle/123?signature=test"),
        )
    }
}

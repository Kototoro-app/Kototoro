package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource

/** Issue #608: plugins linked against NewPipeExtractor (YouTube ones) report why they cannot play here. */
class CloudstreamMissingNewPipeTest {

    @Test
    fun `a plugin missing NewPipe classes fails with a dedicated error`() = runTest {
        val missing = NoClassDefFoundError(
            "Failed resolution of: Lorg/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor;",
        )
        val catalog = catalogOf(FailingApi { throw missing })

        val error = assertThrows<CloudstreamMissingNewPipeException> { catalog.getPages(chapterOf(catalog)) }
        // Coroutine stack-trace recovery may hand back copies, so look for the original by type and message.
        assertCause(missing, error)
        val flowError = assertThrows<CloudstreamMissingNewPipeException> {
            catalog.playbackEvents(chapterOf(catalog)).toList()
        }
        assertCause(missing, flowError)
    }

    @Test
    fun `a wrapped class lookup failure is recognised through its causes`() = runTest {
        val catalog = catalogOf(
            FailingApi {
                throw IllegalStateException(
                    "extractor crashed",
                    ClassNotFoundException("org.schabi.newpipe.extractor.ServiceList"),
                )
            },
        )

        assertThrows<CloudstreamMissingNewPipeException> { catalog.getPages(chapterOf(catalog)) }
    }

    @Test
    fun `links found before the failure still play`() = runTest {
        val catalog = catalogOf(
            FailingApi { callback ->
                callback(newExtractorLink("Test", "Server", "https://example.test/video.m3u8", ExtractorLinkType.M3U8))
                throw NoClassDefFoundError("org/schabi/newpipe/extractor/NewPipe")
            },
        )

        assertEquals("https://example.test/video.m3u8", catalog.getPages(chapterOf(catalog)).single().url)
    }

    @Test
    fun `other missing classes keep the plain empty result`() = runTest {
        val catalog = catalogOf(FailingApi { throw NoClassDefFoundError("com/example/Missing") })

        assertTrue(catalog.getPages(chapterOf(catalog)).isEmpty())
    }

    private fun assertCause(expected: Throwable, error: Throwable) {
        assertTrue(
            generateSequence(error.cause) { it.cause }
                .any { it::class == expected::class && it.message == expected.message },
            "missing cause $expected in $error",
        )
    }

    private fun catalogOf(api: MainAPI) =
        CloudstreamCatalog(CloudstreamSource(api, "test.cs3", "test.newpipe"), object : CloudstreamPlatform {})

    private suspend fun chapterOf(catalog: CloudstreamCatalog) = mapCloudstreamChapters(
        catalog.source.api.newAnimeLoadResponse("Video", "https://example.test/video", TvType.Anime) {
            episodes[DubStatus.Subbed] = listOf(catalog.source.api.newEpisode("https://www.youtube.com/watch?v=abc"))
        },
        catalog.source,
    ).single()

    private class FailingApi(
        private val links: suspend ((ExtractorLink) -> Unit) -> Unit,
    ) : MainAPI() {
        override var name = "NewPipe plugin"
        override var mainUrl = "https://example.test"

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            links(callback)
            return true
        }
    }
}

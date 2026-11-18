package org.skepsun.kototoro.video.player

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.video.domain.VideoCandidate
import javax.inject.Inject

/** Measures the download size of each quality option offered before a video download. */
class VideoQualitySizeProber @Inject constructor(
    @ContentHttpClient httpClient: OkHttpClient,
) {

    private val probe = VideoStreamSizeProbe(httpClient)

    /**
     * Measures every candidate in parallel and reports each result as soon as it is known; candidates
     * whose size cannot be determined within the time limit are simply never reported.
     */
    suspend fun measureEach(
        candidates: List<VideoCandidate>,
        onMeasured: suspend (title: String, size: MeasuredVideoSize) -> Unit,
    ) = coroutineScope {
        candidates.map { candidate ->
            async {
                val size = withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
                    probe.measure(candidate.url, candidate.headers.orEmpty())
                } ?: return@async
                onMeasured(candidate.title.trim(), size)
            }
        }.awaitAll()
    }

    private companion object {
        const val MEASURE_TIMEOUT_MS = 15_000L
    }
}

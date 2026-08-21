package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException

internal class CloudstreamApiGateway(
    private val source: CloudstreamSource,
) {
    suspend fun search(query: String, page: Int): SearchResponseList? = execute(source.api.searchTimeoutMs) {
        source.api.search(query, page)
    }

    suspend fun load(url: String): LoadResponse? {
        if (isInvalidLocator(url)) return null
        return execute(source.api.loadTimeoutMs) {
            source.api.load(source.api.fixUrl(url))
        }?.also { response ->
            response.tags = response.tags?.filter { it.isNotBlank() }
        }
    }

    suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return execute(source.api.getMainPageTimeoutMs) {
            source.api.getMainPage(page, request)
        }
    }

    suspend fun prepareMainPageRequest() {
        waitForMainPageDelay()
        source.api.lastHomepageRequest = System.currentTimeMillis()
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
    ): CloudstreamLoadLinksResult {
        val execution = executeOrNull(source.api.loadLinksTimeoutMs) {
            CloudstreamRequestContext.withLoadLinksCompatibility {
                source.api.loadLinks(data, isCasting, subtitleCallback, linkCallback)
            }
        } ?: return CloudstreamLoadLinksResult(success = false, challenge = null)
        return CloudstreamLoadLinksResult(
            success = execution.value,
            challenge = execution.challenge,
        )
    }

    private suspend fun <T> executeOrNull(timeoutMillis: Long?, block: suspend () -> T): T? {
        return CloudstreamRequestContext.withSource(source) {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMillis.coerceCloudstreamTimeout()) {
                    block()
                }
            }
        }
    }

    private suspend fun waitForMainPageDelay() {
        val waitMillis = source.api.sequentialMainPageScrollDelay -
            (System.currentTimeMillis() - source.api.lastHomepageRequest)
        if (waitMillis > 0) delay(waitMillis)
    }

    private suspend fun <T> execute(timeoutMillis: Long?, block: suspend () -> T): T {
        return CloudstreamRequestContext.withSource(source) {
            withContext(Dispatchers.IO) {
                withTimeout(timeoutMillis.coerceCloudstreamTimeout()) {
                    block()
                }
            }
        }
    }

    private fun Long?.coerceCloudstreamTimeout(): Long {
        return (this ?: DEFAULT_TIMEOUT_MILLIS).coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)
    }

    private fun isInvalidLocator(value: String): Boolean {
        return value.isBlank() || value == "[]" || value == "about:blank"
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val MIN_TIMEOUT_MILLIS = 5_000L
        const val MAX_TIMEOUT_MILLIS = DEFAULT_TIMEOUT_MILLIS * 4
    }
}

internal data class CloudstreamLoadLinksResult(
    val success: Boolean,
    val challenge: CloudFlareProtectedException?,
)

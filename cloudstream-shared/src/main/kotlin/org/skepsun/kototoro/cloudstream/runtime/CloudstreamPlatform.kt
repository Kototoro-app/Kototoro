package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource

/**
 * What the shared Cloudstream engine ([CloudstreamCatalog]) needs from its host. Android supplies its logging,
 * Cloudflare exceptions and WebView resolver; the Windows host lets its shared HTTP client clear challenges at the
 * network level, so its challenge hooks stay at the defaults.
 */
interface CloudstreamPlatform {
    val isDebug: Boolean get() = false
    fun debug(message: String) {}
    fun warn(message: String, error: Throwable? = null) {}
    fun error(message: String, error: Throwable? = null) {}

    /** Runs plugin code on behalf of [source]; its requests get the source's headers (see [CloudstreamRequestScope]). */
    suspend fun <T> withSource(source: CloudstreamSource, block: suspend () -> T): T =
        CloudstreamRequestScope.withSource(source, block)

    /**
     * Runs a `loadLinks` call. A host may let challenge pages through so the plugin can try its other mirrors, and
     * report the first challenge it saw for a retry after the plugin gave up.
     */
    suspend fun <T> withLoadLinks(block: suspend () -> T): CloudstreamLoadLinksExecution<T> =
        CloudstreamLoadLinksExecution(block(), null)

    /** The challenge, attributed to [source], that caused [error]; null when [error] is no challenge. */
    fun findChallenge(error: Throwable, source: CloudstreamSource): Throwable? = null

    /** Tries to clear [challenge]; true when the failed call is worth retrying. */
    suspend fun resolveChallenge(challenge: Throwable, url: String, stage: String): Boolean = false

    /** A short description of the cookies sent to [url], for diagnostics. */
    fun cookieSummary(url: String): String = ""

    /** Debug-only diagnostics when a main page produced nothing at all. */
    suspend fun diagnoseEmptyMainPage(source: CloudstreamSource, request: MainPageRequest, slot: Int, page: Int) {}
}

data class CloudstreamLoadLinksExecution<T>(val value: T, val challenge: Throwable?)

/**
 * The Cloudstream source a plugin call runs for, carried across the plugin's threads, and the request headers a
 * source's requests get when the plugin leaves them out (User-Agent, its site as Referer, an Origin for POSTs) —
 * what the official app's requests carry.
 */
object CloudstreamRequestScope {
    private val currentSource = ThreadLocal<CloudstreamSource?>()

    /** The User-Agent for requests without one; Cloudstream's own by default. */
    @Volatile
    var userAgent: String? = null

    fun current(): CloudstreamSource? = currentSource.get()

    suspend fun <T> withSource(source: CloudstreamSource, block: suspend () -> T): T =
        withContext(currentSource.asContextElement(source)) { block() }

    /** [request] with the headers [source] supplies when missing. */
    fun withSourceHeaders(request: Request, source: CloudstreamSource): Request.Builder {
        val referer = request.header(REFERER)?.takeIf { it.isNotBlank() } ?: (source.api.mainUrl.trimEnd('/') + "/")
        return request.newBuilder().apply {
            val agent = userAgent
            if (request.header(USER_AGENT).isNullOrBlank() && !agent.isNullOrBlank()) header(USER_AGENT, agent)
            if (request.header(REFERER).isNullOrBlank()) header(REFERER, referer)
            if (request.method.uppercase() !in setOf("GET", "HEAD") && request.header(ORIGIN).isNullOrBlank()) {
                referer.toHttpUrlOrNull()?.let { header(ORIGIN, "${it.scheme}://${it.host}") }
            }
        }
    }

    /** Applies [withSourceHeaders] to requests made inside [withSource]. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val source = current()
        chain.proceed(if (source == null) chain.request() else withSourceHeaders(chain.request(), source).build())
    }

    private const val USER_AGENT = "User-Agent"
    private const val REFERER = "Referer"
    private const val ORIGIN = "Origin"
}

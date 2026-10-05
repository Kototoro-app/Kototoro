package org.skepsun.kototoro.cloudstream.runtime

import android.util.Log
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CloudFlareHandlingPolicy
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.parsers.model.ContentSource
import java.util.concurrent.atomic.AtomicReference

internal object CloudstreamRequestContext {

    private val currentPolicy = ThreadLocal<CloudFlareHandlingPolicy?>()

    /** Shared with the Windows host: [CloudstreamRequestScope] owns the source context and default headers. */
    var userAgent: String?
        get() = CloudstreamRequestScope.userAgent
        set(value) { CloudstreamRequestScope.userAgent = value }

    suspend fun <T> withSource(source: CloudstreamSource, block: suspend () -> T): T =
        CloudstreamRequestScope.withSource(source, block)

    suspend fun <T> withLoadLinksCompatibility(block: suspend () -> T): LoadLinksExecution<T> {
        val challenge = AtomicReference<CloudFlareProtectedException?>()
        val policy = CloudFlareHandlingPolicy(
            allowBrowserTransport = false,
            allowBlockedResponse = true,
            allowCaptchaResponse = true,
            onCaptchaDetected = { detected -> challenge.compareAndSet(null, detected) },
        )
        val value = withContext(currentPolicy.asContextElement(policy)) {
            block()
        }
        return LoadLinksExecution(value, challenge.get())
    }

    data class LoadLinksExecution<T>(
        val value: T,
        val challenge: CloudFlareProtectedException?,
    )

    fun interceptor(): Interceptor = Interceptor { chain ->
        val source = CloudstreamRequestScope.current()
        val policy = currentPolicy.get()
        val originalRequest = chain.request()
        val request = if (source != null) {
            CloudstreamRequestScope.withSourceHeaders(originalRequest, source)
                .tag(ContentSource::class.java, source)
                .tag(CloudFlareHandlingPolicy::class.java, policy ?: SOURCE_SOLVER_POLICY)
                .header(CommonHeaders.MANGA_SOURCE, source.name)
                .build()
        } else {
            originalRequest
        }
        val response = chain.proceed(request)
        if (BuildConfig.DEBUG && source != null && policy?.allowBlockedResponse == true) {
            Log.d(
                TAG,
                "loadLinks request source=${source.displayName} code=${response.code} url=${request.url} " +
                    "referer=${request.header(CommonHeaders.REFERER)?.take(160)} origin=${request.header(ORIGIN)}",
            )
            if (shouldLogLoadLinksBody(request.url.toString())) {
                val bodyPreview = runCatching {
                    response.peekBody(LOAD_LINKS_BODY_PREVIEW_BYTES).string()
                }.getOrElse { error ->
                    "<peek failed: ${error::class.simpleName}>"
                }
                Log.d(
                    TAG,
                    "loadLinks response source=${source.displayName} code=${response.code} url=${request.url} " +
                        "iframes=${extractIframeUrls(bodyPreview)} preview=${sanitizePreview(bodyPreview)}",
                )
            }
        }
        response
    }

    private fun shouldLogLoadLinksBody(url: String): Boolean {
        return url.contains("wp-admin/admin-ajax.php", ignoreCase = true) ||
            url.contains("/video-frame/", ignoreCase = true) ||
            url.contains("/video-embed/", ignoreCase = true) ||
            url.contains("sbface.com", ignoreCase = true) ||
            url.contains("voe.sx", ignoreCase = true) ||
            url.contains("nontonanimeid.bio", ignoreCase = true)
    }

    private fun extractIframeUrls(body: String): List<String> {
        return Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(8)
            .toList()
    }

    private fun sanitizePreview(body: String): String {
        return body
            .replace(Regex("\\s+"), " ")
            .take(800)
    }

    private const val TAG = "CloudstreamRequest"
    private const val ORIGIN = "Origin"
    private const val LOAD_LINKS_BODY_PREVIEW_BYTES = 4096L
    private val SOURCE_SOLVER_POLICY = CloudFlareHandlingPolicy(allowBrowserTransport = false)
}

package org.skepsun.kototoro.core.image

import android.util.Log
import android.net.Uri
import androidx.core.net.toUri
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.util.DebugLogger
import coil3.util.Logger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.network.CloudflareHostCooldown
import org.skepsun.kototoro.core.util.ext.bypassFailureCooldownKey
import org.skepsun.kototoro.core.util.ext.mangaKey
import java.io.File

/**
 * Coil interceptor that short-circuits clearly deterministic failures and applies a short,
 * host-scoped Cloudflare cooldown to cover requests.
 *
 * Design notes (2026-08):
 * - Blank data and missing local files are deterministic and short-circuited for every request.
 * - Transient server errors (5xx) are NOT negatively cached: a weak VPN must be able to retry
 *   the same cover as soon as the network recovers, instead of showing a blank placeholder for
 *   ten minutes.
 * - Cloudflare-protected 403s cool the whole host for a short window via [CloudflareHostCooldown]
 *   instead of permanently failing one specific URL. While the host is cooling down, new cover
 *   requests for that host are skipped without touching the network; after the window expires
 *   the same covers are attempted again and can succeed.
 * - User-initiated refreshes can bypass the cooldown by setting [bypassFailureCooldownKey].
 */
class ImageFailureSuppressingInterceptor(
    private val cloudflareHostCooldown: CloudflareHostCooldown = CloudflareHostCooldown(),
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.hasBlankStringData()) {
            return ErrorResult(
                image = request.error(),
                request = request,
                throwable = SuppressedImageRequestException("blank image data"),
            )
        }
        val identity = request.imageIdentity()
        if (request.shouldDeterministicShortCircuit(identity)) {
            return ErrorResult(
                image = request.error(),
                request = request,
                throwable = SuppressedImageRequestException(identity.orEmpty()),
            )
        }
        val isCover = identity != null && request.isCoverRequest(identity)
        val host = identity.hostOf()
        val bypass = request.extras[bypassFailureCooldownKey] == true
        if (isCover && !bypass && cloudflareHostCooldown.isInCooldown(host)) {
            return ErrorResult(
                image = request.error(),
                request = request,
                throwable = SuppressedImageRequestException("cloudflare host cooldown: $host"),
            )
        }

        val result = chain.proceed()
        logResult(request, result)
        if (isCover && result is ErrorResult && result.throwable.isCloudflareProtected()) {
            cloudflareHostCooldown.coolDown(host)
        }
        return result
    }

    private fun ImageRequest.shouldDeterministicShortCircuit(identity: String?): Boolean {
        if (identity == null) {
            return false
        }
        return isMissingAppCacheFile(identity)
    }

    private fun Throwable.isCloudflareProtected(): Boolean {
        if (this is CloudFlareException) {
            return true
        }
        if (this is HttpException && response.code == HTTP_FORBIDDEN) {
            val delegate = response.delegate
            if (delegate is Response) {
                return delegate.message.contains("cloudflare", ignoreCase = true)
            }
        }
        return false
    }

    private fun ImageRequest.isCoverRequest(identity: String): Boolean {
        if (memoryCacheKey?.startsWith(SHARED_COVER_KEY_PREFIX) == true ||
            diskCacheKey?.startsWith(SHARED_COVER_KEY_PREFIX) == true
        ) {
            return true
        }
        if (extras[mangaKey] != null) {
            return true
        }
        val lower = identity.lowercase()
        return COVER_URL_MARKERS.any(lower::contains)
    }

    private fun ImageRequest.isMissingAppCacheFile(identity: String): Boolean {
        if (!identity.startsWith("file:", ignoreCase = true)) {
            return false
        }
        // java.net.URI (rather than android.net.Uri) so this is also testable in JVM unit tests.
        val path = runCatching { java.net.URI(identity).path }.getOrNull() ?: return false
        return !File(path).isFile
    }

    private fun ImageRequest.hasBlankStringData(): Boolean = data is String && (data as String).isBlank()

    private fun ImageRequest.imageIdentity(): String? = when (val value = data) {
        is String -> value.takeIf { it.isNotBlank() }
        is Uri -> value.toString()
        is File -> value.toUri().toString()
        else -> memoryCacheKey ?: diskCacheKey
    }

    private fun String?.hostOf(): String = when (this) {
        null -> ""
        // OkHttp (rather than android.net.Uri) so host extraction also works in JVM unit tests.
        else -> runCatching { toHttpUrlOrNull()?.host }.getOrNull().orEmpty()
    }

    private fun logResult(request: ImageRequest, result: ImageResult) {
        if (!BuildConfig.DEBUG) {
            return
        }
        when (result) {
            is ErrorResult -> {
                if (result.throwable is SuppressedImageRequestException) {
                    return
                }
                Log.e(
                    IMAGE_DIAG_TAG,
                    buildString {
                        append("error ")
                        append(request.debugSignature())
                        append(" throwable=")
                        append(result.throwable::class.java.simpleName)
                        append(':')
                        append(result.throwable.message.orEmpty())
                    },
                )
            }
            is SuccessResult -> {
                if (!request.isHomeSharedCoverRequest()) {
                    return
                }
                Log.d(
                    IMAGE_DIAG_TAG,
                    "home_success ${request.debugSignature()} source=${result.dataSource}",
                )
            }
        }
    }

    private fun ImageRequest.isHomeSharedCoverRequest(): Boolean {
        return memoryCacheKey?.contains("#home_shared_", ignoreCase = false) == true ||
            diskCacheKey?.contains("#home_shared_", ignoreCase = false) == true
    }

    private fun ImageRequest.debugSignature(): String {
        return buildString {
            append("dataType=").append(data?.javaClass?.name ?: "null")
            append(" data=").append(data.debugDataValue())
            append(" memoryKey=").append(memoryCacheKey.orEmpty())
            append(" diskKey=").append(diskCacheKey.orEmpty())
            append(" hasManga=").append(extras[mangaKey] != null)
        }
    }

    private fun Any?.debugDataValue(): String {
        val raw = when (this) {
            null -> "null"
            is String -> this
            is Uri -> toString()
            is File -> toUri().toString()
            else -> toString()
        }
        return raw.replace('\n', ' ').take(240)
    }

    private companion object {
        private const val HTTP_FORBIDDEN = 403
        private const val SHARED_COVER_KEY_PREFIX = "shared-cover#"
        private const val IMAGE_DIAG_TAG = "ImageRequestDiag"
        private val COVER_URL_MARKERS = listOf("/cover", "/covers", "/poster", "/posters", "cover.", "poster.")
    }
}

class SuppressingCoilLogger : Logger {

    private val delegate = DebugLogger()

    override var minLevel: Logger.Level
        get() = delegate.minLevel
        set(value) {
            delegate.minLevel = value
        }

    override fun log(tag: String, level: Logger.Level, message: String?, throwable: Throwable?) {
        if (throwable is SuppressedImageRequestException) {
            return
        }
        delegate.log(tag, level, message, throwable)
    }
}

class SuppressedImageRequestException(
    identity: String,
) : IllegalStateException("Image request suppressed: $identity")

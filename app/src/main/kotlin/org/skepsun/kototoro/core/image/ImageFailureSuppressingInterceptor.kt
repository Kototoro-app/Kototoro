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
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.util.ext.mangaKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ImageFailureSuppressingInterceptor : Interceptor {

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
		if (request.shouldShortCircuit(identity)) {
			return ErrorResult(
				image = request.error(),
				request = request,
				throwable = SuppressedImageRequestException(identity.orEmpty()),
			)
		}

		val result = chain.proceed()
		logResult(request, result)
		if (identity != null && result is ErrorResult && request.shouldRememberFailure(identity, result.throwable)) {
			ImageFailureRegistry.mark(identity)
		}
		return result
	}

	private fun ImageRequest.shouldShortCircuit(identity: String?): Boolean {
		if (identity == null) {
			return false
		}
		return isMissingAppCacheFile(identity) || ImageFailureRegistry.isSuppressed(identity)
	}

	private fun ImageRequest.shouldRememberFailure(identity: String?, error: Throwable): Boolean {
		if (identity == null || !isCoverRequest(identity)) {
			return false
		}
		return error is CloudFlareProtectedException ||
			(error is HttpException && (error.response.code == HTTP_FORBIDDEN || error.response.code >= HTTP_SERVER_ERROR_MIN))
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
		val file = runCatching { Uri.parse(identity).path?.let(::File) }.getOrNull() ?: return false
		return !file.isFile
	}

	private fun ImageRequest.hasBlankStringData(): Boolean = data is String && (data as String).isBlank()

	private fun ImageRequest.imageIdentity(): String? = when (val value = data) {
		is String -> value.takeIf { it.isNotBlank() }
		is Uri -> value.toString()
		is File -> value.toUri().toString()
		else -> memoryCacheKey ?: diskCacheKey
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
		private const val HTTP_SERVER_ERROR_MIN = 500
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
) : IllegalStateException("Image request suppressed after repeated failure: $identity")

private object ImageFailureRegistry {

	private const val SUPPRESSION_WINDOW_MS = 10 * 60 * 1000L

	private val failures = ConcurrentHashMap<String, Long>()
	fun isSuppressed(identity: String): Boolean {
		val failedAt = failures[identity] ?: return false
		val isActive = System.currentTimeMillis() - failedAt < SUPPRESSION_WINDOW_MS
		if (!isActive) {
			failures.remove(identity, failedAt)
		}
		return isActive
	}

	fun mark(identity: String) {
		failures[identity] = System.currentTimeMillis()
	}
}

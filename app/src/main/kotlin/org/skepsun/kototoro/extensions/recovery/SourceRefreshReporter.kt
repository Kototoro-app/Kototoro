package org.skepsun.kototoro.extensions.recovery

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.skepsun.kototoro.core.db.dao.SourceRefreshStateDao
import org.skepsun.kototoro.core.db.entity.SourceRefreshStateEntity
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException

/**
 * Records per-content refresh bookkeeping against a strict source origin (plan T3B.4).
 *
 * Row semantics, mirrored by [RoomSourceRefreshReporter]:
 *  - [SourceRefreshStateEntity.lastSuccessAt] advances ONLY on a fully successful refresh
 *    (details plus chapters). A failure never rewrites it — [recordFailure] leaves the last
 *    success timestamp untouched.
 *  - [recordFailure] writes [SourceRefreshStateEntity.lastError] and refreshes the attempt /
 *    update timestamps; a later [recordSuccess] clears [SourceRefreshStateEntity.lastError].
 *  - [recordAttempt] is an idempotent read-merge-upsert: it only advances the attempt /
 *    update timestamps and preserves the previous success and error fields.
 *
 * Cancellation ([kotlinx.coroutines.CancellationException]) is deliberately not handled here:
 * the caller decides whether a cancelled refresh should be recorded at all.
 */
interface SourceRefreshReporter {

    suspend fun recordAttempt(
        sourceKey: String,
        contentId: Long,
        now: Long = System.currentTimeMillis(),
    )

    suspend fun recordSuccess(
        sourceKey: String,
        contentId: Long,
        now: Long = System.currentTimeMillis(),
    )

    suspend fun recordFailure(
        sourceKey: String,
        contentId: Long,
        error: String?,
    )
}

/** No-op default so repositories stay constructible without a DAO. */
object NoOpSourceRefreshReporter : SourceRefreshReporter {

    override suspend fun recordAttempt(sourceKey: String, contentId: Long, now: Long) = Unit

    override suspend fun recordSuccess(sourceKey: String, contentId: Long, now: Long) = Unit

    override suspend fun recordFailure(sourceKey: String, contentId: Long, error: String?) = Unit
}

/**
 * DAO-backed [SourceRefreshReporter].
 *
 * Every write is a read-merge-upsert against the composite primary key
 * `(sourceKey, contentId)`: the existing row, when present, is copied with only the fields
 * owned by the event mutated; when absent a fresh row is inserted. `upsert` is idempotent.
 */
@Singleton
class RoomSourceRefreshReporter @Inject constructor(
    private val dao: SourceRefreshStateDao,
) : SourceRefreshReporter {

    override suspend fun recordAttempt(
        sourceKey: String,
        contentId: Long,
        now: Long,
    ) {
        val existing = dao.get(sourceKey, contentId)
        val next = existing
            ?.copy(lastAttemptAt = now, updatedAt = now)
            ?: SourceRefreshStateEntity(
                sourceKey = sourceKey,
                contentId = contentId,
                lastAttemptAt = now,
                updatedAt = now,
            )
        dao.upsert(next)
    }

    override suspend fun recordSuccess(
        sourceKey: String,
        contentId: Long,
        now: Long,
    ) {
        val existing = dao.get(sourceKey, contentId)
        val next = existing
            ?.copy(lastSuccessAt = now, lastError = null, updatedAt = now)
            ?: SourceRefreshStateEntity(
                sourceKey = sourceKey,
                contentId = contentId,
                lastSuccessAt = now,
                updatedAt = now,
            )
        dao.upsert(next)
    }

    override suspend fun recordFailure(
        sourceKey: String,
        contentId: Long,
        error: String?,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.get(sourceKey, contentId)
        val next = existing
            ?.copy(lastAttemptAt = now, lastError = error, updatedAt = now)
            ?: SourceRefreshStateEntity(
                sourceKey = sourceKey,
                contentId = contentId,
                lastAttemptAt = now,
                lastError = error,
                updatedAt = now,
            )
        dao.upsert(next)
    }
}

/**
 * Single-line, copyable, sanitized refresh diagnostics (plan T3B.6).
 *
 * Pure Kotlin — no Android dependencies, so it is unit-testable on the JVM. Every URL that
 * ends up in an output line is sanitized: the userinfo segment is stripped and the values of
 * sensitive query parameters (`token`, `password`, `api_key`, `key`, ...) are masked as
 * `***`. The base URL, path, and non-sensitive query parameters are preserved.
 */
object SourceRefreshDiagnostics {

    private const val MASK = "***"

    /** Sensitive query parameter names whose values are masked, matched case-insensitively. */
    private val SENSITIVE_QUERY_KEYS = listOf(
        "token",
        "password",
        "passwd",
        "pwd",
        "api_key",
        "apikey",
        "api-key",
        "key",
    )

    private val QUERY_REDACTION = Regex(
        "([?&](?:${SENSITIVE_QUERY_KEYS.joinToString("|") { Regex.escape(it) }})=)[^&#]*",
        RegexOption.IGNORE_CASE,
    )

    private val USERINFO_REDACTION = Regex("""(https?://)[^@/\s]+@""", RegexOption.IGNORE_CASE)

    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""")

    private val WHITESPACE = Regex("\\s+")

    private data class Category(
        val prefix: String,
        val retryable: Boolean,
    )

    /**
     * Redacts a URL for diagnostics: strips the userinfo segment and masks the values of the
     * sensitive query parameters named in [SENSITIVE_QUERY_KEYS]. The base URL, path, and
     * non-sensitive query parameters are preserved. Unrecognized strings are returned unchanged.
     */
    fun sanitizeUrl(url: String): String {
        val withoutUserinfo = USERINFO_REDACTION.replace(url) { m -> m.groupValues[1] }
        return QUERY_REDACTION.replace(withoutUserinfo) { m -> "${m.groupValues[1]}$MASK" }
    }

    /**
     * Composes a one-line, copyable diagnostic summary. The message is collapsed onto a single
     * line and any embedded URL is sanitized; `packageName` is kept verbatim.
     */
    fun summary(
        sourceKey: String,
        packageName: String?,
        phase: String,
        message: String,
    ): String {
        val oneLine = WHITESPACE.replace(message, " ").trim()
        val sanitized = URL_IN_TEXT.replace(oneLine) { m -> sanitizeUrl(m.value) }
        val pkg = packageName?.takeIf { it.isNotBlank() }?.let { "pkg=$it " }.orEmpty()
        return "$sourceKey $pkg" + "phase=$phase $sanitized"
    }

    /**
     * Builds a diagnostic line from a failing phase and [Throwable]: category prefix + message,
     * plus a retry hint for [IOException] and a sanitized URL when the error carries one.
     * [kotlinx.coroutines.CancellationException] is deliberately not special-cased — callers
     * skip recording cancelled refreshes.
     */
    fun classify(
        sourceKey: String,
        phase: String,
        error: Throwable,
        packageName: String? = null,
    ): String {
        val category = when (error) {
            is CloudFlareException -> Category("[cloudflare]", retryable = false)
            is InteractiveActionRequiredException -> Category("[action-required]", retryable = false)
            is IOException -> Category("[io]", retryable = true)
            else -> Category("[error]", retryable = false)
        }
        val text = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        val url = when (error) {
            is CloudFlareException -> error.url.takeIf { it.isNotBlank() }
            is InteractiveActionRequiredException -> error.url.takeIf { it.isNotBlank() }
            else -> null
        }
        val message = buildString {
            append(category.prefix)
            append(' ')
            append(text)
            if (category.retryable) {
                append(" (retryable)")
            }
            if (url != null) {
                append(" url=")
                append(sanitizeUrl(url))
            }
        }
        return summary(sourceKey, packageName, phase, message)
    }
}

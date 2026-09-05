package org.skepsun.kototoro.core.exceptions.resolve

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.BrowserActivity
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.CloudflareStrategy
import org.skepsun.kototoro.core.network.webview.CaptchaAutoResolveResult
import org.skepsun.kototoro.core.ui.util.ForegroundActivityHolder
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaptchaAutoResolveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundActivityHolder: ForegroundActivityHolder,
    private val webViewExecutor: WebViewExecutor,
    @ContentHttpClient private val httpClient: OkHttpClient,
    private val settings: AppSettings,
) {

    private val singleFlight = CloudFlareSingleFlight()
    private val manualMutex = Mutex()
    private val pendingActivityResult = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val resolverState = CloudFlareResolverState()

    fun notifyResolveResult(resolveKey: String, success: Boolean) {
        pendingActivityResult.remove(resolveKey)?.complete(success)
    }

    suspend fun resolve(
        source: ContentSource,
        exception: CloudFlareProtectedException,
        tryAutomatic: Boolean = true,
    ): Boolean {
        return resolveInternal(
            source = source,
            exception = exception,
            tryAutomatic = tryAutomatic,
            allowInteractiveFallback = true,
            showToast = tryAutomatic,
        )
    }

    suspend fun resolveInBackground(source: ContentSource, exception: CloudFlareProtectedException): Boolean {
        return resolveInternal(
            source = source,
            exception = exception,
            tryAutomatic = true,
            allowInteractiveFallback = false,
            showToast = false,
        )
    }

    private suspend fun resolveInternal(
        source: ContentSource,
        exception: CloudFlareProtectedException,
        tryAutomatic: Boolean,
        allowInteractiveFallback: Boolean,
        showToast: Boolean,
    ): Boolean {
        val host = exception.url.resolveHostKey()
        return singleFlight.run(host) {
            runOrchestration(
                source = source,
                exception = exception,
                host = host,
                tryAutomatic = tryAutomatic,
                allowInteractiveFallback = allowInteractiveFallback,
                showToast = showToast,
            )
        }
    }

    private suspend fun runOrchestration(
        source: ContentSource,
        exception: CloudFlareProtectedException,
        host: String,
        tryAutomatic: Boolean,
        allowInteractiveFallback: Boolean,
        showToast: Boolean,
    ): Boolean {
        return try {
            val context = CloudFlareRequestContext.from(exception)
            // WebView transport 仅在该策略被显式选择时参与自动求解；MIHON 策略在拦截器内联求解，
            // 失败后由此处走人工浏览器兜底；MANUAL 直接走人工浏览器。
            val effectiveTryAutomatic = tryAutomatic && settings.cloudflareStrategy == CloudflareStrategy.TRANSPORT
            val plan = resolverState.plan(host, effectiveTryAutomatic, allowInteractiveFallback)
            if (plan == CloudFlareResolvePlan.FAIL_FAST) {
                logResolve(host, context, plan = plan)
                return false
            }
            if (showToast && plan.runAutomatic) {
                showSolvingToast()
            }
            val automaticResult = if (plan.runAutomatic) {
                webViewExecutor.resolveCaptchaAutomatically(
                    exception = exception,
                    timeout = WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS,
                )
            } else {
                null
            }
            if (automaticResult == CaptchaAutoResolveResult.SOLVED) {
                val cleared = probeCleared(context)
                logResolve(host, context, plan = plan, automaticResult = automaticResult, probeCleared = cleared)
                if (cleared) {
                    resolverState.recordSuccess(host, CloudFlareResolveStage.AUTOMATIC)
                    true
                } else {
                    // A new clearance was issued but the real request still fails: treat as
                    // failure and cool down instead of reporting a false success.
                    resolverState.recordFailure(host)
                    false
                }
            } else {
                logResolve(host, context, plan = plan, automaticResult = automaticResult)
                if (plan.runManual) {
                    manualMutex.withLock {
                        launchAndAwait(source, exception, host)
                    }.let { resolved ->
                        val cleared = resolved && probeCleared(context)
                        logResolve(host, context, plan = plan, manualResult = resolved, probeCleared = cleared)
                        when {
                            !resolved -> Unit
                            cleared -> resolverState.recordSuccess(host, CloudFlareResolveStage.MANUAL)
                            else -> resolverState.recordFailure(host)
                        }
                        cleared
                    }
                } else false
            }
        } catch (e: Throwable) {
            e.printStackTraceDebug()
            false
        }
    }

    private fun logResolve(
        host: String,
        context: CloudFlareRequestContext,
        plan: CloudFlareResolvePlan? = null,
        automaticResult: CaptchaAutoResolveResult? = null,
        manualResult: Boolean? = null,
        probeCleared: Boolean? = null,
    ) {
        android.util.Log.d(
            TAG,
            buildString {
                append("host=").append(host)
                append(" source=").append(context.source.name)
                append(" method=").append(context.method)
                append(" challengeUrl=").append(context.challengeUrl.take(240))
                append(" originalUrl=").append(context.originalRequestUrl.take(240))
                append(" userAgentPresent=").append(!context.userAgent.isNullOrBlank())
                append(" headerNames=").append(context.headers.keys.sortedBy(String::lowercase))
                append(" bodyLength=").append(context.body?.length ?: 0)
                plan?.let { append(" plan=").append(it) }
                automaticResult?.let { append(" automaticResult=").append(it) }
                manualResult?.let { append(" manualResult=").append(it) }
                probeCleared?.let { append(" probeCleared=").append(it) }
            },
        )
    }

    private suspend fun probeCleared(context: CloudFlareRequestContext): Boolean {
        repeat(PROBE_MAX_ATTEMPTS) { attempt ->
            if (probeClearedOnce(context)) return true
            if (attempt < PROBE_MAX_ATTEMPTS - 1) delay(PROBE_RETRY_DELAY_MS)
        }
        return false
    }

    private suspend fun probeClearedOnce(context: CloudFlareRequestContext): Boolean {
        val url = context.originalRequestUrl.takeIf { it.isNotBlank() } ?: return false
        val contentType = context.headers["Content-Type"]?.toMediaTypeOrNull()
        val request = Request.Builder().url(url).apply {
            context.headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank() && name.lowercase() !in PROBE_SKIP_HEADERS) {
                    addHeader(name, value)
                }
            }
            val body = context.body
            if (context.method == "POST" && body != null) {
                method("POST", body.toRequestBody(contentType))
            }
        }.build()
        return try {
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            response.use {
                CloudFlareHelper.checkResponseForProtection(it) == CloudFlareHelper.PROTECTION_NOT_DETECTED
            }
        } catch (e: CloudFlareProtectedException) {
            false
        } catch (e: CloudFlareBlockedException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun launchAndAwait(
        source: ContentSource,
        exception: CloudFlareProtectedException,
        resolveKey: String,
    ): Boolean {
        if (source == UnknownContentSource) {
            android.util.Log.w(TAG, "Manual Cloudflare resolver skipped: source is unknown url=${exception.url}")
            return false
        }
        val launcher = foregroundActivityHolder.current
        val resultDeferred = CompletableDeferred<Boolean>()
        pendingActivityResult[resolveKey] = resultDeferred
        val intent = AppRouter.cloudFlareResolveIntent(context, exception).apply {
            putExtra(BrowserActivity.EXTRA_CF_RESOLVE_KEY, resolveKey)
        }
        android.util.Log.i(
            TAG,
            "Launching manual Cloudflare browser: source=${source.name} " +
                "url=${intent.dataString}",
        )
        launcher?.startActivity(intent) ?: run {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        return try {
            // Bounded await: BrowserActivity normally reports through notifyResolveResult(),
            // but if the activity is destroyed without ever finishing (system-driven kill of
            // a backgrounded activity) the deferred would otherwise hang manualMutex and
            // every later manual resolution for the lifetime of the process.
            val result = withTimeoutOrNull(MANUAL_RESOLUTION_TIMEOUT_MS) {
                resultDeferred.await()
            }
            if (result == null) {
                android.util.Log.w(
                    TAG,
                    "Manual Cloudflare resolution timed out: source=${source.name} url=${exception.url}",
                )
                false
            } else {
                result
            }
        } finally {
            pendingActivityResult.remove(resolveKey, resultDeferred)
        }
    }

    private fun showSolvingToast() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.captcha_solving, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "CaptchaAutoResolver"
        const val PROBE_MAX_ATTEMPTS = 10
        const val PROBE_RETRY_DELAY_MS = 1_000L
        const val MANUAL_RESOLUTION_TIMEOUT_MS = 10 * 60 * 1000L

        // Browser/transport managed headers that the OkHttp probe must not re-emit
        // manually; cookies are handled by the shared CookieJar.
        private val PROBE_SKIP_HEADERS = setOf(
            "host",
            "content-length",
            "cookie",
            "connection",
            "accept-encoding",
            "transfer-encoding",
        )
    }
}

internal enum class CloudFlareResolveStage {
    AUTOMATIC,
    MANUAL,
}

internal enum class CloudFlareResolvePlan(
    val runAutomatic: Boolean,
    val runManual: Boolean,
) {
    AUTO_THEN_MANUAL(runAutomatic = true, runManual = true),
    AUTO_ONLY(runAutomatic = true, runManual = false),
    MANUAL_ONLY(runAutomatic = false, runManual = true),
    FAIL_FAST(runAutomatic = false, runManual = false),
}

internal class CloudFlareResolverState(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Success(
        val stage: CloudFlareResolveStage,
        val timestamp: Long,
    )

    private val lastSuccess = ConcurrentHashMap<String, Success>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    fun plan(host: String, tryAutomatic: Boolean, allowManual: Boolean): CloudFlareResolvePlan {
        val now = nowMillis()
        cooldownUntil[host]?.let { until ->
            if (until > now) return CloudFlareResolvePlan.FAIL_FAST
            cooldownUntil.remove(host, until)
        }
        val recent = lastSuccess[host]?.takeIf { now - it.timestamp < SUCCESS_RETRY_WINDOW_MS }
        if (recent == null) {
            lastSuccess.remove(host)
            return when {
                tryAutomatic && allowManual -> CloudFlareResolvePlan.AUTO_THEN_MANUAL
                tryAutomatic -> CloudFlareResolvePlan.AUTO_ONLY
                allowManual -> CloudFlareResolvePlan.MANUAL_ONLY
                else -> CloudFlareResolvePlan.FAIL_FAST
            }
        }
        return when (recent.stage) {
            CloudFlareResolveStage.AUTOMATIC -> {
                if (allowManual) CloudFlareResolvePlan.MANUAL_ONLY else CloudFlareResolvePlan.FAIL_FAST
            }
            CloudFlareResolveStage.MANUAL -> {
                lastSuccess.remove(host, recent)
                cooldownUntil[host] = now + RESOLVER_COOLDOWN_MS
                CloudFlareResolvePlan.FAIL_FAST
            }
        }
    }

    fun recordSuccess(host: String, stage: CloudFlareResolveStage) {
        cooldownUntil.remove(host)
        lastSuccess[host] = Success(stage, nowMillis())
    }

    fun recordFailure(host: String) {
        lastSuccess.remove(host)
        cooldownUntil[host] = nowMillis() + RESOLVER_COOLDOWN_MS
    }

    private companion object {
        const val SUCCESS_RETRY_WINDOW_MS = RESOLVER_COOLDOWN_MS
    }
}

private const val RESOLVER_COOLDOWN_MS = 2 * 60 * 1000L

private fun String.resolveHostKey(): String = runCatching {
    URI(this).host?.lowercase()
}.getOrNull().orEmpty().ifBlank { this }

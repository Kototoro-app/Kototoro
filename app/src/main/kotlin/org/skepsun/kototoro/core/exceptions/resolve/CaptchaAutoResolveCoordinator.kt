package org.skepsun.kototoro.core.exceptions.resolve

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.cloudflare.CloudFlareActivity
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.network.webview.CaptchaAutoResolveResult
import org.skepsun.kototoro.core.ui.util.ForegroundActivityHolder
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentSource
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaptchaAutoResolveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundActivityHolder: ForegroundActivityHolder,
    private val webViewExecutor: WebViewExecutor,
) {

    private val hostMutexes = ConcurrentHashMap<String, Mutex>()
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
        return hostMutexes.getOrPut(host) { Mutex() }.withLock {
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
            val plan = resolverState.plan(host, tryAutomatic, allowInteractiveFallback)
            if (plan == CloudFlareResolvePlan.FAIL_FAST) {
                android.util.Log.w(TAG, "Resolver is cooling down for host=$host source=${source.name}")
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
            android.util.Log.d(TAG, "host=$host plan=$plan automaticResult=$automaticResult")
            if (automaticResult == CaptchaAutoResolveResult.SOLVED) {
                resolverState.recordSuccess(host, CloudFlareResolveStage.AUTOMATIC)
                true
            } else if (plan.runManual) {
                manualMutex.withLock {
                    launchAndAwait(source, exception, host)
                }.also { resolved ->
                    if (resolved) {
                        resolverState.recordSuccess(host, CloudFlareResolveStage.MANUAL)
                    }
                }
            } else false
        } catch (e: Throwable) {
            e.printStackTraceDebug()
            false
        }
    }

    private suspend fun launchAndAwait(
        source: ContentSource,
        exception: CloudFlareProtectedException,
        resolveKey: String,
    ): Boolean {
        if (source == UnknownContentSource) {
            return false
        }
        val launcher = foregroundActivityHolder.current
        val resultDeferred = CompletableDeferred<Boolean>()
        pendingActivityResult[resolveKey] = resultDeferred
        val intent = AppRouter.cloudFlareResolveIntent(context, exception, hidden = false).apply {
            putExtra(CloudFlareActivity.EXTRA_AUTO_RESOLVE, true)
            putExtra(CloudFlareActivity.EXTRA_RESOLVE_KEY, resolveKey)
        }
        launcher?.startActivity(intent) ?: run {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        return try {
            resultDeferred.await()
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

    private companion object {
        const val SUCCESS_RETRY_WINDOW_MS = RESOLVER_COOLDOWN_MS
    }
}

private const val RESOLVER_COOLDOWN_MS = 2 * 60 * 1000L

private fun String.resolveHostKey(): String = runCatching {
    URI(this).host?.lowercase()
}.getOrNull().orEmpty().ifBlank { this }

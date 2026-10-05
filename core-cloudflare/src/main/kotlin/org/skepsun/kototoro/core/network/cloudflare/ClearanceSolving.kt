package org.skepsun.kototoro.core.network.cloudflare

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.core.network.webview.CloudFlarePageState
import org.skepsun.kototoro.core.network.webview.CloudflareSolveCoordinator
import java.util.Locale

/*
 * Mihon's default Cloudflare solver (CloudflareInterceptor/WebViewInterceptor, also used by Komikku), shared by
 * Android (WebView) and Windows (WebView2): load the challenged URL in an off-screen browser that shares the cookie
 * store, wait up to 30 seconds for a new `cf_clearance`, then retry the original request once.
 */

/** How long one off-screen solve may take before falling back. */
const val CLEARANCE_SOLVE_TIMEOUT_MS: Long = 30_000L

/** Mihon removes these before solving, so a freshly written cookie is the success signal. */
val CLEARANCE_COOKIE_NAMES: List<String> = listOf("cf_clearance")

/** Main-frame statuses on which Cloudflare serves its challenge page. */
val CLEARANCE_CHALLENGE_STATUS_CODES: Set<Int> = setOf(403, 503)

private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")

/**
 * Mihon's trigger: a 403/503 from a Cloudflare server, or Cloudflare's explicit `cf-mitigated: challenge` marker.
 * Callers with richer detection (Android's `CloudFlareHelper`) may use their own.
 */
fun isMihonCloudflareChallenge(code: Int, server: String?, cfMitigated: String?): Boolean =
    cfMitigated.equals("challenge", ignoreCase = true) ||
        (code in CLEARANCE_CHALLENGE_STATUS_CODES && server?.lowercase(Locale.ENGLISH) in CLOUDFLARE_SERVERS)

fun Response.isMihonCloudflareChallenge(): Boolean =
    isMihonCloudflareChallenge(code, header("Server"), header("cf-mitigated"))

/** Browser engines reject some request headers (Chromium's IsRequestHeaderSafe, as Mihon ports it). */
private val UNSAFE_HEADER_NAMES = setOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding", "set-cookie",
)

fun isBrowserRequestHeaderSafe(rawName: String, rawValue: String): Boolean {
    val name = rawName.lowercase(Locale.ENGLISH)
    val value = rawValue.lowercase(Locale.ENGLISH)
    if (name in UNSAFE_HEADER_NAMES || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}

/** The request's headers a browser accepts, first value per name; cookies come from the shared store instead. */
fun Headers.safeForBrowser(): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (i in 0 until size) {
        val name = name(i)
        val value = value(i)
        if (!name.equals("Cookie", ignoreCase = true) && isBrowserRequestHeaderSafe(name, value)) {
            result.putIfAbsent(name, value)
        }
    }
    return result
}

enum class ClearanceSolveDecision { WAIT, SOLVED, FAILED }

/**
 * One solve's progress, fed by browser events (Android) or polls (Windows), mirroring Mihon's rules:
 * a new clearance cookie solves it; a challenge status on the main frame means "wait for the JS challenge"; any
 * other main-frame error, or the requested page finishing without a challenge, means it cannot be solved here.
 */
class ClearanceSolveTracker(private val requestUrl: String) {
    var challengeFound: Boolean = false
        private set

    fun onMainFrameHttpError(status: Int): ClearanceSolveDecision =
        if (status in CLEARANCE_CHALLENGE_STATUS_CODES) {
            challengeFound = true
            ClearanceSolveDecision.WAIT
        } else {
            ClearanceSolveDecision.FAILED
        }

    fun onPageFinished(finishedUrl: String, hasNewClearance: Boolean): ClearanceSolveDecision = when {
        hasNewClearance -> ClearanceSolveDecision.SOLVED
        finishedUrl == requestUrl && !challengeFound -> ClearanceSolveDecision.FAILED
        else -> ClearanceSolveDecision.WAIT
    }

    private var interactiveSince: Long? = null

    /**
     * For engines without page events, from the page state script ([org.skepsun.kototoro.core.network.webview.CF_STATE_JS]).
     * A hard block cannot pass, and a checkbox challenge that stays past [INTERACTIVE_GRACE_MS] (Turnstile often shows
     * its widget briefly and passes by itself) needs a human, so both fail before the timeout and the caller can hand
     * over to the user.
     */
    fun onPageState(state: CloudFlarePageState, hasNewClearance: Boolean, nowMs: Long): ClearanceSolveDecision {
        if (hasNewClearance) return ClearanceSolveDecision.SOLVED
        if (state != CloudFlarePageState.INTERACTIVE_CHALLENGE) interactiveSince = null
        return when (state) {
            CloudFlarePageState.MANAGED_CHALLENGE -> {
                challengeFound = true
                ClearanceSolveDecision.WAIT
            }
            CloudFlarePageState.INTERACTIVE_CHALLENGE -> {
                challengeFound = true
                val since = interactiveSince ?: nowMs.also { interactiveSince = it }
                if (nowMs - since >= INTERACTIVE_GRACE_MS) ClearanceSolveDecision.FAILED else ClearanceSolveDecision.WAIT
            }
            CloudFlarePageState.HARD_BLOCK -> ClearanceSolveDecision.FAILED
            CloudFlarePageState.NORMAL -> if (challengeFound) ClearanceSolveDecision.WAIT else ClearanceSolveDecision.FAILED
            CloudFlarePageState.LOADING -> ClearanceSolveDecision.WAIT
        }
    }

    companion object {
        const val INTERACTIVE_GRACE_MS: Long = 8_000L
    }
}

/** A platform browser that solves a request's challenge, returning true once a new clearance is stored. */
fun interface ClearanceSolver {
    suspend fun solve(request: Request): Boolean
}

/**
 * Solves the request's host once (concurrent requests to the host share the solve through [coordinator]) and retries
 * this request once. Returns null when the challenge could not be solved, so the caller can fall back (manual
 * verification or its own error); the challenged response must already be closed.
 */
fun Interceptor.Chain.solveClearanceAndRetry(
    request: Request,
    solver: ClearanceSolver,
    coordinator: CloudflareSolveCoordinator?,
    /** Polled while waiting; a cancelled caller stops waiting (and the solve stops once no caller waits). */
    isCancelled: () -> Boolean = { call().isCanceled() },
): Response? {
    val solved = runCatching {
        runBlocking {
            coroutineScope {
                val work = async {
                    coordinator?.solve(request.url.host) { solver.solve(request) } ?: solver.solve(request)
                }
                val watcher = launch {
                    while (isActive) {
                        if (isCancelled()) work.cancel()
                        delay(CANCELLATION_POLL_MS)
                    }
                }
                try {
                    work.await()
                } finally {
                    watcher.cancel()
                }
            }
        }
    }.getOrDefault(false)
    return if (solved && !isCancelled()) proceed(request) else null
}

private const val CANCELLATION_POLL_MS = 100L

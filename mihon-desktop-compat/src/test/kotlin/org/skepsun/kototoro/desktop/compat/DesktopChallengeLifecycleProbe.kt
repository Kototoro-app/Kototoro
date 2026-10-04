package org.skepsun.kototoro.desktop.compat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

internal object DesktopChallengeLifecycleProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try { runBlocking {
            val root = Path.of(args[1])
            FileSourcePreferenceStore(root.resolve("preferences")).use { preferences ->
                val platform = MihonDesktopPlatform(root.resolve("compat"))
                platform.initialize(preferences)
                try {
                    check(platform.browserChallenges == null) { "Headless platform must not install UI interaction" }
                    LocalBrowserSite().use { site ->
                        DesktopBrowserChallenges(File(args[2]), root.resolve("browser"), platform::cookieBridge,
                            // The deadline includes native startup; allow a cold WebView2 profile under a full-suite load.
                            timeoutMs = 15_000).use { challenges ->
                            val request = Request.Builder().url(site.url("/interaction")).build()
                            val client = OkHttpClient()
                            val first = async(Dispatchers.IO) {
                                runCatching { challenges.resolve(request, client.newCall(request)) }
                            }
                            withTimeout(20_000) {
                                while (challenges.pending.value == null && !first.isCompleted) delay(20)
                            }
                            val active = checkNotNull(challenges.pending.value) {
                                "Browser did not become interactive before its deadline: ${first.await().exceptionOrNull()}"
                            }
                            if (args[0] == "timeout") {
                                val error = withTimeout(16_000) { first.await() }.exceptionOrNull()
                                check(error is IOException && error.cause is TimeoutCancellationException) { "$error" }
                            } else {
                                val queuedCall = client.newCall(request)
                                val second = async(Dispatchers.IO) { runCatching { challenges.resolve(request, queuedCall) } }
                                queuedCall.cancel()
                                check(withTimeout(3000) { second.await() }.isFailure)
                                check(challenges.pending.value == active)
                                challenges.complete(active.id, false)
                                check(withTimeout(3000) { first.await() }.isFailure)
                            }
                            check(challenges.pending.value == null)
                        }
                    }
                } finally { platform.close() }
            }
            awaitBrowserProfileRelease(root)
            println("CHALLENGE_LIFECYCLE_OK=${args[0]}")
        } } catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
    }
}

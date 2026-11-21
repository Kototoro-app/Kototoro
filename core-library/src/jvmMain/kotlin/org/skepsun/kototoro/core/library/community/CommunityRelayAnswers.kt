package org.skepsun.kototoro.core.library.community

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Which DNS-over-HTTPS answer a relay name resolves to, for Android's and the desktop's relay resolvers (community plan
 * §5.1). All resolvers are asked at once; DNSPod's answer is used only when Cloudflare and Google cannot be reached. On
 * 2026-11-21 DNSPod answered `nos.lol` with a poisoned address (Cloudflare: 142.132.206.70), so a reader whose VPN
 * carries all traffic and reaches Cloudflare would otherwise have been sent there; one without a VPN still gets
 * DNSPod's answer within [INTERNATIONAL_GRACE_MILLIS].
 */
object CommunityRelayAnswers {
    private const val INTERNATIONAL_GRACE_MILLIS = 1_500L
    private const val ANSWER_TIMEOUT_MILLIS = 7_000L

    /** Runs the blocking resolver calls; daemon threads, so a lookup never keeps a process alive. */
    val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "community-relay-dns").apply { isDaemon = true }
    }

    /** [domestic] is DNSPod's lookup, [international] the others'; an empty list means that resolver failed. */
    fun <T> preferred(domestic: Future<List<T>>, international: List<Future<List<T>>>): List<T> {
        fun firstInternational(waitMillis: Long): List<T>? {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
            while (System.nanoTime() < until) {
                international.firstOrNull { it.isDone && it.get().isNotEmpty() }?.let { return it.get() }
                if (international.all { it.isDone }) return null
                Thread.sleep(25)
            }
            return null
        }
        firstInternational(INTERNATIONAL_GRACE_MILLIS)?.let { return it }
        val local = runCatching { domestic.get(ANSWER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }.getOrNull().orEmpty()
        if (local.isNotEmpty()) return local
        return firstInternational(ANSWER_TIMEOUT_MILLIS).orEmpty()
    }
}

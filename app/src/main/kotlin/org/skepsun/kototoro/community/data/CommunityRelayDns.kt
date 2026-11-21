package org.skepsun.kototoro.community.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.skepsun.kototoro.core.library.community.CommunityRelayAnswers
import java.util.concurrent.TimeUnit

/**
 * Name resolution for relay connections only. Relay domains are poisoned on mainland China's plain DNS (nos.lol came back
 * as a Twitter address, relay.primal.net as a Facebook one) while their real addresses are reachable, and the
 * international DoH services are blocked there too. Measured 2026-11-20 on a phone in China: AliDNS DoH returned poisoned
 * answers, DNSPod's (doh.pub) returned the same addresses as Cloudflare's. On 2026-11-21 DNSPod poisoned `nos.lol` too, so
 * all three are asked at once and DNSPod's answer is used only when Cloudflare and Google cannot be reached
 * ([CommunityRelayAnswers]); the platform's own resolver only when every one of them fails.
 */
class CommunityRelayDns(base: OkHttpClient, private val fallback: Dns) : Dns {

    // Short timeouts: an unreachable resolver should cost seconds, not the relay's whole connect timeout.
    private val bootstrap = base.newBuilder()
        .dns(Dns.SYSTEM)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val resolvers: List<Dns> = listOf(
        doh("https://doh.pub/dns-query", "1.12.12.12", "120.53.53.53"),
        doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
        doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
    )

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val answers = resolvers.map { resolver ->
            CompletableFuture.supplyAsync({ runCatching { resolver.lookup(hostname) }.getOrNull().orEmpty() },
                CommunityRelayAnswers.executor)
        }
        // DNSPod first in the list, but only its answer when the others cannot be reached (it poisons some names too).
        val addresses = CommunityRelayAnswers.preferred(answers.first(), answers.drop(1))
        if (addresses.isNotEmpty()) {
            cache[hostname] = Entry(addresses, now + CACHE_MILLIS)
            return addresses
        }
        return fallback.lookup(hostname)
    }

    private fun doh(url: String, vararg bootstrapIps: String): Dns = DnsOverHttps.Builder()
        .client(bootstrap)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
        .includeIPv6(false)
        .build()

    private companion object {
        const val CACHE_MILLIS = 10 * 60 * 1000L
    }
}

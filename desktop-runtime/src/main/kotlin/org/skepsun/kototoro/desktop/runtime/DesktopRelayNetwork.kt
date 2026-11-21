package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.skepsun.kototoro.community.nostr.NostrRelayConnection
import org.skepsun.kototoro.community.nostr.NostrRelayConnector
import org.skepsun.kototoro.core.library.community.CommunityRelayAnswers
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android's `CommunityRelayDns` for the desktop: relay names only. Plain DNS in mainland China answers relay domains
 * with other sites' addresses, and the international DoH services are blocked there, so all three are asked at once:
 * Cloudflare's or Google's answer when either comes back, DNSPod's (`doh.pub`) when neither can be reached, and the
 * system resolver only when all of them fail ([CommunityRelayAnswers], community plan §5.1). A proxied connection never gets
 * here: the proxy resolves the name itself.
 *
 * The pinned desktop runtime ships OkHttp without its DoH module, so this sends RFC 8484 queries itself: one `A`
 * question per name, IPv4 only, as Android's resolver is configured.
 */
class DesktopRelayDns(base: OkHttpClient, private val fallback: Dns = Dns.SYSTEM) : Dns {

    private class Server(val url: String, val host: String, val addresses: List<String>)

    private val servers = listOf(
        Server("https://doh.pub/dns-query", "doh.pub", listOf("1.12.12.12", "120.53.53.53")),
        Server("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1")),
        Server("https://dns.google/dns-query", "dns.google", listOf("8.8.8.8", "8.8.4.4")),
    )

    // The resolvers' own names come from fixed addresses (they would be poisoned too); short timeouts, so a blocked
    // resolver costs seconds rather than the relay's whole connect timeout.
    private val client = base.newBuilder()
        .dns { host -> servers.firstOrNull { it.host == host }?.addresses?.map(InetAddress::getByName) ?: Dns.SYSTEM.lookup(host) }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val answers = servers.map { server ->
            CompletableFuture.supplyAsync({ runCatching { query(server, hostname) }.getOrNull().orEmpty() }, CommunityRelayAnswers.executor)
        }
        val addresses = CommunityRelayAnswers.preferred(answers.first(), answers.drop(1))
        if (addresses.isNotEmpty()) {
            cache[hostname] = Entry(addresses, now + CACHE_MILLIS)
            return addresses
        }
        return fallback.lookup(hostname)
    }

    private fun query(server: Server, hostname: String): List<InetAddress> {
        val message = Base64.getUrlEncoder().withoutPadding().encodeToString(encodeQuery(hostname))
        val request = Request.Builder().url("${server.url}?dns=$message")
            .header("Accept", "application/dns-message").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            decodeAddresses(response.body.bytes()).map { InetAddress.getByAddress(hostname, it) }
        }
    }

    companion object {
        private const val CACHE_MILLIS = 10 * 60 * 1000L
        /** A recursive `A` query for [hostname] in DNS wire format, id 0 as RFC 8484 recommends for caching. */
        fun encodeQuery(hostname: String): ByteArray = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            hostname.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.size in 1..63) { "invalid DNS label in $hostname" }
                write(bytes.size)
                write(bytes)
            }
            write(byteArrayOf(0, 0, 1, 0, 1))
        }.toByteArray()

        /** Every IPv4 address among the answers of a wire-format response; CNAMEs and other records are skipped. */
        fun decodeAddresses(message: ByteArray): List<ByteArray> {
            if (message.size < 12) throw IOException("short DNS response")
            fun u16(at: Int) = ((message[at].toInt() and 0xff) shl 8) or (message[at + 1].toInt() and 0xff)
            if (u16(2) and 0x000f != 0) throw IOException("DNS error ${u16(2) and 0x000f}")
            fun skipName(start: Int): Int {
                var at = start
                while (true) {
                    val length = message[at].toInt() and 0xff
                    when {
                        length == 0 -> return at + 1
                        length and 0xc0 == 0xc0 -> return at + 2
                        else -> at += length + 1
                    }
                }
            }
            var at = 12
            repeat(u16(4)) { at = skipName(at) + 4 }
            val addresses = mutableListOf<ByteArray>()
            repeat(u16(6)) {
                at = skipName(at)
                val type = u16(at)
                val length = u16(at + 8)
                at += 10
                if (type == 1 && length == 4) addresses += message.copyOfRange(at, at + 4)
                at += length
            }
            return addresses
        }
    }
}

/**
 * Nostr relays over OkHttp's WebSocket, so a relay connection takes the same route as every other desktop request:
 * the proxy page's selector and credentials (HTTP or SOCKS), and [DesktopRelayDns] when it connects directly.
 */
class OkHttpNostrRelayConnector(private val client: OkHttpClient) : NostrRelayConnector {

    override suspend fun connect(url: String): NostrRelayConnection {
        val opened = CompletableDeferred<Unit>()
        val messages = Channel<String>(Channel.UNLIMITED)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.close()
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                messages.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                messages.close()
            }
        })
        try {
            opened.await()
        } catch (error: Throwable) {
            socket.cancel()
            throw error
        }
        return object : NostrRelayConnection {
            override suspend fun send(text: String) {
                if (!socket.send(text)) throw IOException("$url is closed")
            }

            override suspend fun receive(): String? = messages.receiveCatching().getOrNull()

            override suspend fun close() {
                socket.close(1000, null)
                messages.close()
            }
        }
    }
}

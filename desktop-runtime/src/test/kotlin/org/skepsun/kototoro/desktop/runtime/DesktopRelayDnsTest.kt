package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CompletableFuture

class DesktopRelayDnsTest {
    @Test
    fun `a query asks recursively for one A record`() {
        val query = DesktopRelayDns.encodeQuery("nos.lol")
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 'n'.code.toByte(), 'o'.code.toByte(),
            's'.code.toByte(), 3, 'l'.code.toByte(), 'o'.code.toByte(), 'l'.code.toByte(), 0, 0, 1, 0, 1), query)
    }

    @Test
    fun `answers keep the IPv4 addresses and skip a CNAME before them`() {
        val question = DesktopRelayDns.encodeQuery("relay.example").copyOfRange(12, DesktopRelayDns.encodeQuery("relay.example").size)
        val header = byteArrayOf(0, 0, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3, 0, 0, 0, 0)
        // CNAME relay.example -> c.example (pointer to the question name for the owner), then two A records.
        val cname = byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 11, 1, 'c'.code.toByte(), 7) +
            "example".toByteArray() + byteArrayOf(0)
        val a1 = byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 104, 21, 0, 1)
        val a2 = byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 172.toByte(), 67, 0, 2)
        val addresses = DesktopRelayDns.decodeAddresses(header + question + cname + a1 + a2)
        assertEquals(listOf("104.21.0.1", "172.67.0.2"),
            addresses.map { bytes -> bytes.joinToString(".") { (it.toInt() and 0xff).toString() } })
    }

    @Test
    fun `an international answer wins over DNSPod's, which is used when they cannot be reached`() {
        val poisoned = CompletableFuture.completedFuture(listOf("108.160.163.116"))
        val real = CompletableFuture.completedFuture(listOf("142.132.206.70"))
        val blocked = CompletableFuture.completedFuture(emptyList<String>())
        assertEquals(listOf("142.132.206.70"), org.skepsun.kototoro.core.library.community.CommunityRelayAnswers.preferred(poisoned, listOf(blocked, real)))
        assertEquals(listOf("108.160.163.116"), org.skepsun.kototoro.core.library.community.CommunityRelayAnswers.preferred(poisoned, listOf(blocked, blocked)))
        val never = CompletableFuture<List<String>>()
        val started = System.nanoTime()
        assertEquals(listOf("108.160.163.116"), org.skepsun.kototoro.core.library.community.CommunityRelayAnswers.preferred(poisoned, listOf(never)))
        assert(System.nanoTime() - started < 3_000_000_000L) { "a silent resolver costs the grace period, not the timeout" }
    }

    @Test
    fun `an error response is not an empty answer`() {
        val failure = byteArrayOf(0, 0, 0x81.toByte(), 0x83.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertThrows<IOException> { DesktopRelayDns.decodeAddresses(failure) }
    }
}

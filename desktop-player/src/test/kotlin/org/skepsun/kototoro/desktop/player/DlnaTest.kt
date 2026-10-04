package org.skepsun.kototoro.desktop.player

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake renderer (SSDP answer + description + SOAP control) and a fake site that wants a Referer: discovery,
 * control and the relay are exercised end to end on the loopback interface.
 */
class DlnaTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private class Site(val server: HttpServer, val seen: MutableList<String>) : AutoCloseable {
        val base get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun site(): Site {
        val seen = CopyOnWriteArrayList<String>()
        val segment = ByteArray(4096) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        fun guard(exchange: com.sun.net.httpserver.HttpExchange, body: ByteArray, type: String) {
            seen += "${exchange.requestURI.path}|${exchange.requestHeaders.getFirst("Referer")}|${exchange.requestHeaders.getFirst("Range")}"
            if (exchange.requestHeaders.getFirst("Referer") != "https://anime.invalid/") {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return
            }
            val range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Type", type)
            if (range != null) {
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                exchange.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                exchange.sendResponseHeaders(206, (body.size - start).toLong())
                exchange.responseBody.use { it.write(body, start, body.size - start) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.createContext("/hls/master.m3u8") { guard(it, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n".toByteArray(), "application/vnd.apple.mpegurl") }
        server.createContext("/hls/low/index.m3u8") {
            guard(it, ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\n" +
                "/hls/low/seg1.ts\n#EXT-X-ENDLIST\n").toByteArray(), "application/x-mpegURL")
        }
        server.createContext("/hls/key.bin") { guard(it, ByteArray(16) { 7 }, "application/octet-stream") }
        server.createContext("/hls/low/seg0.ts") { guard(it, segment, "video/mp2t") }
        server.createContext("/hls/low/seg1.ts") { guard(it, segment, "video/mp2t") }
        server.createContext("/video.mp4") { guard(it, segment, "video/mp4") }
        server.start()
        return Site(server, seen)
    }

    /** Answers M-SEARCH, serves its description (embedded device, relative URLs) and records SOAP actions. */
    private class Renderer(loopback: InetAddress) : AutoCloseable {
        val actions = CopyOnWriteArrayList<String>()
        val http: HttpServer = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        val udp = DatagramSocket(InetSocketAddress(loopback, 0))
        private val responder: Thread

        init {
            http.createContext("/description.xml") { exchange ->
                val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                    <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType><friendlyName>Hub</friendlyName><deviceList><device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>客厅电视</friendlyName>
                    <UDN>uuid:fake-tv</UDN><serviceList>
                    <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>rc/control</controlURL></service>
                    <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av/control</controlURL></service>
                    </serviceList></device></deviceList></device></root>""".toByteArray()
                exchange.sendResponseHeaders(200, xml.size.toLong()); exchange.responseBody.use { it.write(xml) }
            }
            for (path in listOf("/av/control", "/rc/control")) http.createContext(path) { exchange ->
                val action = exchange.requestHeaders.getFirst("SOAPAction").substringAfter('#').trim('"')
                val body = exchange.requestBody.readAllBytes().decodeToString()
                actions += "$action|$body"
                val reply = when (action) {
                    "GetPositionInfo" -> "<RelTime>0:00:12</RelTime><TrackDuration>0:01:30</TrackDuration>"
                    "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                    else -> ""
                }
                val xml = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response>$reply</u:${action}Response></s:Body></s:Envelope>".toByteArray()
                exchange.sendResponseHeaders(200, xml.size.toLong()); exchange.responseBody.use { it.write(xml) }
            }
            http.start()
            responder = Thread({
                val buffer = ByteArray(2048)
                while (!udp.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { udp.receive(packet) }.getOrNull() ?: break
                    if (!String(packet.data, 0, packet.length).startsWith("M-SEARCH")) continue
                    val reply = ("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n" +
                        "LOCATION: http://127.0.0.1:${http.address.port}/description.xml\r\nUSN: uuid:fake-tv\r\n\r\n").toByteArray()
                    udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                }
            }).apply { isDaemon = true; start() }
        }

        override fun close() { udp.close(); http.stop(0) }
    }

    @Test
    fun `a renderer is found described and controlled`() {
        Renderer(loopback).use { fake ->
            val device = DlnaDiscovery.discover(1500, listOf(InetSocketAddress(loopback, fake.udp.localPort))).single()
            assertEquals("客厅电视", device.name)
            assertEquals("http://127.0.0.1:${fake.http.address.port}/av/control", device.avTransportUrl)
            assertEquals("http://127.0.0.1:${fake.http.address.port}/rc/control", device.renderingControlUrl)
            val renderer = DlnaRenderer(device)
            renderer.load("http://192.0.2.1/v.mp4?a=1&b=2", "第 1 集 <测试>", "video/mp4")
            renderer.play(); renderer.seek(3725.0); renderer.pause(); renderer.setVolume(30)
            val status = renderer.status()
            assertEquals(DlnaStatus("PLAYING", 12.0, 90.0), status)
            renderer.stop()
            val names = fake.actions.map { it.substringBefore('|') }
            assertEquals(listOf("SetAVTransportURI", "Play", "Seek", "Pause", "SetVolume", "GetTransportInfo",
                "GetPositionInfo", "Stop"), names)
            val load = fake.actions.first().substringAfter('|')
            assertTrue(load.contains("<CurrentURI>http://192.0.2.1/v.mp4?a=1&amp;b=2</CurrentURI>"), load)
            assertTrue(load.contains("&lt;dc:title&gt;第 1 集 &amp;lt;测试&amp;gt;&lt;/dc:title&gt;"), load)
            assertTrue(fake.actions[2].contains("<Target>1:02:05</Target>"))
            assertTrue(fake.actions[4].contains("<DesiredVolume>30</DesiredVolume>"))
        }
    }

    @Test
    fun `the relay adds the site's headers passes ranges and routes every HLS uri through itself`() {
        site().use { origin ->
            DlnaStreamProxy(loopback).use { proxy ->
                val client = HttpClient.newHttpClient()
                fun get(url: String, range: String? = null): HttpResponse<ByteArray> = client.send(
                    HttpRequest.newBuilder(URI(url)).apply { range?.let { header("Range", it) } }.GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                val headers = mapOf("Referer" to "https://anime.invalid/", "User-Agent" to "Kototoro")
                // Progressive file with a range, as a renderer seeks.
                val mp4 = proxy.register("${origin.base}/video.mp4", headers, loopback)
                val partial = get(mp4, "bytes=100-")
                assertEquals(206, partial.statusCode())
                assertEquals(4096 - 100, partial.body().size)
                assertEquals("Streaming", partial.headers().firstValue("transferMode.dlna.org").get())
                // HLS: master -> variant -> key and segments, all fetched through the relay with the Referer.
                val master = proxy.register("${origin.base}/hls/master.m3u8", headers, loopback)
                val variantUrl = get(master).body().decodeToString().lines().first { it.startsWith("http") }
                assertTrue(variantUrl.startsWith("http://127.0.0.1:${proxy.port}/cast/"), variantUrl)
                val variant = get(variantUrl).body().decodeToString()
                val uris = variant.lines().filter { it.startsWith("http") } +
                    Regex("URI=\"([^\"]+)\"").findAll(variant).map { it.groupValues[1] }.toList()
                assertEquals(3, uris.size, variant)
                uris.forEach { assertTrue(it.startsWith("http://127.0.0.1:${proxy.port}/cast/"), it) }
                assertArrayEquals(ByteArray(16) { 7 }, get(uris.last()).body())
                assertEquals(4096, get(uris.first()).body().size)
                assertTrue(origin.seen.all { it.split('|')[1] == "https://anime.invalid/" }, origin.seen.toString())
                assertTrue(origin.seen.map { it.substringBefore('|') }.containsAll(
                    listOf("/hls/master.m3u8", "/hls/low/index.m3u8", "/hls/key.bin", "/hls/low/seg0.ts")))
            }
        }
    }
}

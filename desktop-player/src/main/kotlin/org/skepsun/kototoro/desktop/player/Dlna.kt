package org.skepsun.kototoro.desktop.player

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A UPnP media renderer on the local network (a TV, a box, a DLNA player). */
data class DlnaDevice(
    val name: String,
    val location: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
    val udn: String?,
)

/**
 * SSDP discovery, as the Android player does it: M-SEARCH for AVTransport, then each answering device's description.
 * Every IPv4 interface that can multicast sends its own search, so the right LAN is found on multi-homed PCs.
 */
object DlnaDiscovery {
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:AVTransport:1"
    val multicast = InetSocketAddress("239.255.255.250", 1900)

    /**
     * Where to search: the SSDP group, plus any `host:port` listed in `kototoro.dlna.targets` (devices on networks
     * multicast does not reach, and the app's own tests).
     */
    fun defaultTargets(): List<InetSocketAddress> = listOf(multicast) + System.getProperty("kototoro.dlna.targets").orEmpty()
        .split(',').filter { ':' in it }.map { InetSocketAddress(it.substringBeforeLast(':').trim(), it.substringAfterLast(':').trim().toInt()) }

    fun discover(timeoutMillis: Int = 4000, targets: List<InetSocketAddress> = defaultTargets()): List<DlnaDevice> {
        val locations = ConcurrentHashMap.newKeySet<String>()
        val message = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
            "ST: $SEARCH_TARGET\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val sockets = buildList {
            for (target in targets) {
                if (target.address.isMulticastAddress) {
                    for (network in NetworkInterface.networkInterfaces().toList()) {
                        if (!network.isUp || network.isLoopback || !network.supportsMulticast() || network.isVirtual) continue
                        val address = network.inetAddresses.toList().firstOrNull { it is Inet4Address } ?: continue
                        runCatching {
                            MulticastSocket(InetSocketAddress(address, 0)).apply { networkInterface = network; timeToLive = 4 }
                        }.getOrNull()?.let { add(it to target) }
                    }
                } else add(DatagramSocket() to target)
            }
        }
        val threads = sockets.map { (socket, target) ->
            Thread({
                socket.use {
                    try {
                        it.soTimeout = 300
                        repeat(3) { _ -> it.send(DatagramPacket(message, message.size, target)) }
                        val buffer = ByteArray(8192)
                        val deadline = System.currentTimeMillis() + timeoutMillis
                        while (System.currentTimeMillis() < deadline) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            try { it.receive(packet) } catch (_: SocketTimeoutException) { continue }
                            header(String(packet.data, 0, packet.length, Charsets.UTF_8), "LOCATION")?.let(locations::add)
                        }
                    } catch (_: IOException) {
                        // One interface failing (VPN adapters, disabled NICs) does not end the search.
                    }
                }
            }, "dlna-search").apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(timeoutMillis + 2000L) }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        return locations.mapNotNull { location ->
            runCatching {
                val response = http.send(HttpRequest.newBuilder(URI(location)).timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() in 200..299) describe(location, response.body()) else null
            }.getOrNull()
        }.distinctBy { it.udn ?: it.location }.sortedBy { it.name }
    }

    private fun header(response: String, name: String): String? = response.lineSequence()
        .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)

    /** Parses a device description: the first device (root or embedded) with an AVTransport service. */
    internal fun describe(location: String, xml: ByteArray): DlnaDevice? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val base = document.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf(String::isNotBlank) ?: location
        val devices = document.getElementsByTagName("device")
        for (index in 0 until devices.length) {
            val device = devices.item(index) as Element
            val services = device.getElementsByTagName("service")
            var transport: String? = null
            var rendering: String? = null
            for (s in 0 until services.length) {
                val service = services.item(s) as Element
                if (service.parentNode?.parentNode !== device) continue
                val type = service.getElementsByTagName("serviceType").item(0)?.textContent.orEmpty()
                val control = service.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
                when {
                    "AVTransport" in type -> transport = URI(base).resolve(control).toString()
                    "RenderingControl" in type -> rendering = URI(base).resolve(control).toString()
                }
            }
            if (transport == null) continue
            fun child(tag: String) = (0 until device.childNodes.length).map { device.childNodes.item(it) }
                .firstOrNull { it.nodeName == tag }?.textContent?.trim()
            return DlnaDevice(child("friendlyName") ?: URI(location).host, location, transport, rendering, child("UDN"))
        }
        return null
    }
}

/** Playback state a renderer reports. */
data class DlnaStatus(val state: String, val position: Double, val duration: Double)

/** AVTransport / RenderingControl over SOAP. */
class DlnaRenderer(val device: DlnaDevice, private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(4)).build()) {

    fun load(url: String, title: String, mime: String) {
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${xml(url)}</res></item></DIDL-Lite>"""
        transport("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>")
    }

    fun play() { transport("Play", "<Speed>1</Speed>") }
    fun pause() { transport("Pause", "") }
    fun stop() { transport("Stop", "") }
    fun seek(seconds: Double) { transport("Seek", "<Unit>REL_TIME</Unit><Target>${clock(seconds)}</Target>") }

    fun status(): DlnaStatus {
        val transport = transport("GetTransportInfo", "")
        val position = transport("GetPositionInfo", "")
        return DlnaStatus(value(transport, "CurrentTransportState") ?: "UNKNOWN",
            seconds(value(position, "RelTime")), seconds(value(position, "TrackDuration")))
    }

    fun setVolume(volume: Int) {
        val url = device.renderingControlUrl ?: return
        soap(url, "urn:schemas-upnp-org:service:RenderingControl:1", "SetVolume",
            "<Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>")
    }

    private fun transport(action: String, arguments: String) =
        soap(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", action, arguments)

    private fun soap(url: String, service: String, action: String, arguments: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service"><InstanceID>0</InstanceID>$arguments</u:$action></s:Body></s:Envelope>"""
        val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(6))
            .header("Content-Type", "text/xml; charset=\"utf-8\"").header("SOAPAction", "\"$service#$action\"")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val fault = value(response.body(), "errorDescription")
            throw IOException("${device.name} 拒绝 $action（HTTP ${response.statusCode()}${fault?.let { "：$it" } ?: ""}）")
        }
        return response.body()
    }

    private fun value(xml: String, tag: String): String? =
        Regex("<(?:\\w+:)?$tag>([^<]*)</(?:\\w+:)?$tag>").find(xml)?.groupValues?.get(1)?.trim()

    companion object {
        fun clock(seconds: Double): String {
            val total = seconds.coerceAtLeast(0.0).toLong()
            return "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total % 3600 / 60, total % 60)
        }

        fun seconds(text: String?): Double {
            if (text.isNullOrBlank() || text == "NOT_IMPLEMENTED") return 0.0
            val parts = text.substringBefore('.').split(':').mapNotNull(String::toLongOrNull)
            return parts.fold(0L) { total, part -> total * 60 + part }.toDouble()
        }

        private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

        /** The local address this PC uses to reach [device], i.e. what the renderer can connect back to. */
        fun localAddressFor(device: DlnaDevice): InetAddress = DatagramSocket().use { socket ->
            val uri = URI(device.location)
            socket.connect(InetAddress.getByName(uri.host), if (uri.port > 0) uri.port else 80)
            socket.localAddress
        }

        fun mimeOf(url: String): String {
            val path = url.substringBefore('?').lowercase(Locale.ROOT)
            return when {
                path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".ts") -> "video/mp2t"
                else -> "video/mp4"
            }
        }
    }
}

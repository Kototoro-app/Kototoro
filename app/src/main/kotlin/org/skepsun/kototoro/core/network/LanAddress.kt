package org.skepsun.kototoro.core.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Returns the device's WiFi/LAN IPv4 address, or null if unavailable.
     */
    fun getWifiIpAddress(context: Context): String? {
        // Try WifiManager first
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiIp = wifiManager?.connectionInfo?.ipAddress
        if (wifiIp != null && wifiIp != 0) {
            val ip = String.format(
                "%d.%d.%d.%d",
                wifiIp and 0xff,
                wifiIp shr 8 and 0xff,
                wifiIp shr 16 and 0xff,
                wifiIp shr 24 and 0xff,
            )
            if (ip != "0.0.0.0") return ip
        }

        // Fallback: enumerate network interfaces
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

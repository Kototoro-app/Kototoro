package org.skepsun.kototoro.core.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper for Android 17 (API 37+) Local Network Protection (LNP).
 *
 * Apps targeting SDK 37+ are blocked by default from connecting to local network addresses
 * (RFC1918, link-local, CGNAT, multicast, broadcast, `.local`) unless the runtime permission
 * [PERMISSION] is granted by the user.
 */
object LocalNetworkPermissionHelper {

    const val ANDROID_17_API_LEVEL: Int = 37
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= ANDROID_17_API_LEVEL

    fun isGranted(context: Context): Boolean {
        if (!isRequired()) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    fun needsPermission(context: Context): Boolean = isRequired() && !isGranted(context)

    fun needsPermissionForHost(context: Context, hostOrUrl: String?): Boolean {
        return isRequired() && isLocalNetworkHost(hostOrUrl) && !isGranted(context)
    }

    fun createAppSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    /**
     * Returns true if [hostOrUrl] refers to a local network address per Android 17's
     * Local Network Definition (RFC1918, link-local, CGNAT, multicast, broadcast, `.local`),
     * excluding loopback (`localhost`, `127.0.0.0/8`, `::1`).
     *
     * Performs pure string/numeric parsing without DNS resolution so it is safe on the main thread.
     */
    fun isLocalNetworkHost(hostOrUrl: String?): Boolean {
        val host = extractHost(hostOrUrl) ?: return false
        if (host == "localhost" || host == "::1") {
            return false
        }
        if (host == "local" || host.endsWith(".local")) {
            return true
        }
        parseIpv4(host)?.let { octets ->
            return isLocalIpv4(octets)
        }
        if (host.contains(':')) {
            return isLocalIpv6(host)
        }
        return false
    }

    internal fun extractHost(hostOrUrl: String?): String? {
        var value = hostOrUrl?.trim().orEmpty()
        if (value.isEmpty()) return null

        val schemeEnd = value.indexOf("://")
        if (schemeEnd >= 0) {
            value = value.substring(schemeEnd + 3)
        }

        // Strip path, query, fragment
        val pathStart = value.indexOfAny(charArrayOf('/', '?', '#'))
        if (pathStart >= 0) {
            value = value.substring(0, pathStart)
        }

        // Strip userinfo (user:pass@host)
        val atIndex = value.lastIndexOf('@')
        if (atIndex >= 0) {
            value = value.substring(atIndex + 1)
        }

        value = value.trim()
        if (value.isEmpty()) return null

        // Handle bracketed IPv6: [fe80::1]:7890
        if (value.startsWith('[')) {
            val closeBracket = value.indexOf(']')
            if (closeBracket <= 1) return null
            value = value.substring(1, closeBracket)
        } else {
            // Strip :port for IPv4 or hostname (only when there is a single colon)
            val firstColon = value.indexOf(':')
            if (firstColon >= 0 && firstColon == value.lastIndexOf(':')) {
                value = value.substring(0, firstColon)
            }
        }

        // Strip IPv6 zone ID if present (e.g. fe80::1%wlan0)
        val zoneIndex = value.indexOf('%')
        if (zoneIndex >= 0) {
            value = value.substring(0, zoneIndex)
        }

        return value.trim().trimEnd('.').lowercase().takeIf { it.isNotEmpty() }
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) {
                return null
            }
            val num = part.toIntOrNull() ?: return null
            if (num !in 0..255) return null
            octets[i] = num
        }
        return octets
    }

    private fun isLocalIpv4(octets: IntArray): Boolean {
        val b0 = octets[0]
        val b1 = octets[1]
        val b2 = octets[2]
        val b3 = octets[3]
        // Loopback 127.0.0.0/8 is NOT local network
        if (b0 == 127) return false
        return when {
            b0 == 10 -> true // 10.0.0.0/8 (RFC1918)
            b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12 (RFC1918)
            b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 (RFC1918)
            b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 (Link-local)
            b0 == 100 && b1 in 64..127 -> true // 100.64.0.0/10 (CGNAT)
            b0 in 224..239 -> true // 224.0.0.0/4 (Multicast)
            b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255 -> true // Broadcast
            else -> false
        }
    }

    private fun isLocalIpv6(host: String): Boolean {
        // IPv4-mapped IPv6 address (e.g. ::ffff:192.168.10.100)
        if (host.startsWith("::ffff:")) {
            val mappedIpv4 = host.removePrefix("::ffff:")
            parseIpv4(mappedIpv4)?.let { return isLocalIpv4(it) }
        }
        // Loopback ::1 / 0:0:0:0:0:0:0:1
        val normalized = host.split(':')
        if (normalized.all { it.isEmpty() || it.all { ch -> ch == '0' } || it == "1" }) {
            val nonZero = normalized.filter { it.isNotEmpty() && !it.all { ch -> ch == '0' } }
            if (nonZero.isEmpty() || (nonZero.size == 1 && nonZero[0] == "1" && normalized.last() == "1")) {
                return false
            }
        }
        val firstGroup = normalized.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val firstValue = firstGroup.toIntOrNull(16) ?: return false
        return when {
            (firstValue and 0xFFC0) == 0xFE80 -> true // fe80::/10 Link-local
            (firstValue and 0xFE00) == 0xFC00 -> true // fc00::/7 Unique local (ULA)
            (firstValue and 0xFF00) == 0xFF00 -> true // ff00::/8 Multicast
            else -> false
        }
    }
}

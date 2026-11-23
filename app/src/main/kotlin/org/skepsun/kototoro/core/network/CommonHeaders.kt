package org.skepsun.kototoro.core.network

import okhttp3.CacheControl

object CommonHeaders {

    const val REFERER = "Referer"
    const val USER_AGENT = "User-Agent"
    const val ACCEPT = "Accept"
    const val CONTENT_TYPE = "Content-Type"
    const val CONTENT_DISPOSITION = "Content-Disposition"
    const val COOKIE = "Cookie"
    const val CONTENT_ENCODING = "Content-Encoding"
    const val ACCEPT_ENCODING = "Accept-Encoding"
    const val AUTHORIZATION = "Authorization"
    const val CACHE_CONTROL = "Cache-Control"
    const val PROXY_AUTHORIZATION = "Proxy-Authorization"
    const val RETRY_AFTER = "Retry-After"
    const val LAST_MODIFIED = "Last-Modified"
    const val IF_MODIFIED_SINCE = "If-Modified-Since"
    const val MANGA_SOURCE = "X-Content-Source"
    const val ICY_METADATA = "Icy-MetaData"

    val CACHE_CONTROL_NO_STORE: CacheControl
        get() = CacheControl.Builder().noStore().build()

    const val DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"

    /**
     * Source names may contain non-ASCII characters (e.g. Cloudstream providers named in Arabic),
     * which OkHttp rejects in header values. Percent-encode everything outside printable ASCII, and `%` itself,
     * so [decodeSourceName] is an exact inverse.
     */
    fun encodeSourceName(name: String): String {
        if (name.all { it.code in 0x20..0x7E && it != '%' }) return name
        val sb = StringBuilder(name.length + 16)
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            if (v in 0x20..0x7E && v != '%'.code) {
                sb.append(v.toChar())
            } else {
                sb.append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0xF])
            }
        }
        return sb.toString()
    }

    fun decodeSourceName(value: String): String {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '%' && i + 2 < value.length) {
                value.substring(i + 1, i + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

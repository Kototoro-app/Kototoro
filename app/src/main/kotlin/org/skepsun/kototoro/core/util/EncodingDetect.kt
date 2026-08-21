package org.skepsun.kototoro.core.util

import org.jsoup.Jsoup
import org.skepsun.kototoro.lib.icu4j.CharsetDetector

/**
 * Utility for detecting charset encoding from byte arrays.
 * Based on legado-with-MD3 EncodingDetect.
 */
object EncodingDetect {

    private val headTagRegex = "(?i)<head>[\\s\\S]*?</head>".toRegex()
    private val headOpenBytes = "<head>".toByteArray()
    private val headCloseBytes = "</head>".toByteArray()

    /**
     * Detect charset from HTML content.
     * First checks meta tags, then falls back to ICU4J detection.
     */
    fun getHtmlEncode(bytes: ByteArray): String {
        try {
            var head: String? = null
            val startIndex = bytes.indexOf(headOpenBytes)
            if (startIndex > -1) {
                val endIndex = bytes.indexOf(headCloseBytes, startIndex)
                if (endIndex > -1) {
                    head = String(bytes.copyOfRange(startIndex, endIndex + headCloseBytes.size))
                }
            }
            val doc = Jsoup.parseBodyFragment(head ?: headTagRegex.find(String(bytes))?.value ?: "")
            val metaTags = doc.getElementsByTag("meta")
            var charsetStr: String
            for (metaTag in metaTags) {
                charsetStr = metaTag.attr("charset")
                if (charsetStr.isNotEmpty()) {
                    return normalizeCharset(charsetStr)
                }
                val httpEquiv = metaTag.attr("http-equiv")
                if (httpEquiv.equals("content-type", true)) {
                    val content = metaTag.attr("content")
                    val idx = content.indexOf("charset=", ignoreCase = true)
                    charsetStr = if (idx > -1) {
                        content.substring(idx + "charset=".length)
                    } else {
                        content.substringAfter(";")
                    }
                    if (charsetStr.isNotEmpty()) {
                        return normalizeCharset(charsetStr)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return getEncode(bytes)
    }

    /**
     * Detect charset using ICU4J CharsetDetector.
     */
    fun getEncode(bytes: ByteArray): String {
        return try {
            val match = CharsetDetector().setText(bytes).detect()
            match?.name ?: "UTF-8"
        } catch (e: Exception) {
            "UTF-8"
        }
    }

    /**
     * Normalize charset name (e.g., "gb2312" -> "GBK")
     */
    private fun normalizeCharset(charset: String): String {
        val upper = charset.trim().uppercase()
        return when {
            upper in listOf("GBK", "GB2312", "GB18030") -> "GBK"
            upper == "BIG5" -> "Big5"
            else -> charset.trim()
        }
    }

    /**
     * Find byte array index (helper for head detection)
     */
    private fun ByteArray.indexOf(target: ByteArray, startFrom: Int = 0): Int {
        if (target.isEmpty()) return 0
        outer@ for (i in startFrom..(this.size - target.size)) {
            for (j in target.indices) {
                if (this[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

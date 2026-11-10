package org.skepsun.kototoro.core.parser.legado

/**
 * Small, generic URL normalization helpers for Legado sources.
 *
 * 注意：这里不做站点特判，只处理在多站点中常见的“协议省略”和“错误的图片后缀变换”两类问题。
 */
internal object LegadoUrlSanitizer {

    private val knownImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jxl")

    fun sanitizeImageUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed

        // Scheme-less URL: //cdn.example.com/a.jpg
        val withScheme = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed

        // Some sources generate a transformed suffix like:
        //   https://.../cover-750x999.!cover-400
        // which is not a valid image URL on many CDNs (404). Recover the original extension.
        val queryIndex = withScheme.indexOf('?').let { if (it == -1) withScheme.length else it }
        val fragmentIndex = withScheme.indexOf('#').let { if (it == -1) withScheme.length else it }
        val cutIndex = minOf(queryIndex, fragmentIndex)
        val path = withScheme.substring(0, cutIndex)
        val suffix = withScheme.substring(cutIndex)

        val lastSlash = path.lastIndexOf('/')
        val transformIndex = path.lastIndexOf(".!")
        if (transformIndex > lastSlash) {
            val filePart = path.substring(lastSlash + 1)
            val ext = filePart.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val hasKnownExt = ext.isNotEmpty() && ext in knownImageExtensions
            if (!hasKnownExt) {
                val restored = path.substring(0, transformIndex) + ".jpg"
                return restored + suffix
            }
        }

        return withScheme
    }
}


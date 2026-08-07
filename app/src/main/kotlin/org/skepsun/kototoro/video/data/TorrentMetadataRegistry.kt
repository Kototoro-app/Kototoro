package org.skepsun.kototoro.video.data

import java.net.URI
import java.net.URLDecoder

internal object TorrentMetadataRegistry {
    private const val MAX_ENTRIES = 16
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024

    private val metadataByHash = LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true)
    private var storedBytes = 0

    @Synchronized
    fun put(infoHash: String, metadata: ByteArray) {
        if (metadata.size > MAX_TOTAL_BYTES) return
        metadataByHash.remove(infoHash.lowercase())?.let { storedBytes -= it.size }
        while (metadataByHash.size >= MAX_ENTRIES || storedBytes + metadata.size > MAX_TOTAL_BYTES) {
            val eldest = metadataByHash.entries.firstOrNull() ?: break
            storedBytes -= eldest.value.size
            metadataByHash.remove(eldest.key)
        }
        metadataByHash[infoHash.lowercase()] = metadata.copyOf()
        storedBytes += metadata.size
    }

    @Synchronized
    fun find(locator: String): ByteArray? {
        val hash = extractInfoHash(locator) ?: return null
        return metadataByHash[hash.lowercase()]?.copyOf()
    }

    internal fun extractInfoHash(locator: String): String? {
        if (!locator.startsWith("magnet:", ignoreCase = true)) return null
        val query = runCatching { URI(locator).rawQuery }.getOrNull()
            ?: locator.substringAfter('?', missingDelimiterValue = "")
        return query.split('&')
            .asSequence()
            .mapNotNull { parameter ->
                val parts = parameter.split('=', limit = 2)
                if (!parts.first().equals("xt", ignoreCase = true)) return@mapNotNull null
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                    .substringAfter("urn:btih:", missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }
}

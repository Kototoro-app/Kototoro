package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Artwork fields shared by episode metadata from different source ecosystems. */
@Serializable
data class ChapterArtwork(
    val posterUrl: String,
    val posterHeaders: Map<String, String> = emptyMap(),
)

object ChapterArtworkCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(artwork: ChapterArtwork): String = json.encodeToString(artwork)

    /** Also accepts existing Cloudstream episode metadata without changing its persisted format. */
    fun decode(sourceData: String?): ChapterArtwork? {
        if (sourceData.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ChapterArtwork>(sourceData) }.getOrNull()
            ?.takeIf { it.posterUrl.isNotBlank() }
    }
}

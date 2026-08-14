package org.skepsun.kototoro.video.domain

import android.net.Uri
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PlaybackSubtitle(
    val id: String,
    val uri: Uri?,
    val label: String,
    val languageTag: String?,
    val origin: SubtitleOrigin,
    val mimeType: String?,
    val headers: Map<String, String>,
) {
    companion object {
        fun external(
            url: String,
            label: String,
            languageTag: String? = null,
            origin: SubtitleOrigin,
            mimeType: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): PlaybackSubtitle {
            require(origin != SubtitleOrigin.EMBEDDED) { "External subtitle cannot use EMBEDDED origin" }
            return PlaybackSubtitle(
                id = stablePlaybackSubtitleId(origin, url),
                uri = Uri.parse(url),
                label = label,
                languageTag = languageTag,
                origin = origin,
                mimeType = mimeType,
                headers = headers,
            )
        }
    }
}

enum class SubtitleOrigin {
    EMBEDDED,
    ANIYOMI_EXTERNAL,
    CLOUDSTREAM_EXTERNAL,
    LOCAL_FILE,
}

internal fun stablePlaybackSubtitleId(origin: SubtitleOrigin, identity: String): String {
    val value = "$origin\u0000$identity"
    return "subtitle:${UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))}"
}

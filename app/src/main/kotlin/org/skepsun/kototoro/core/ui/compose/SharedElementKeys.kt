package org.skepsun.kototoro.core.ui.compose

import org.skepsun.kototoro.parsers.model.Content

fun contentCoverIdentity(
    content: Content,
    coverUrl: String?,
): String {
    return coverUrl?.takeIf { it.isNotBlank() }
        ?: content.url.takeIf { it.isNotBlank() }
        ?: content.publicUrl.takeIf { it.isNotBlank() }
        ?: content.id.toString()
}

fun contentCoverSharedKey(
    sourceName: String,
    url: String,
    instanceKey: String? = null,
): String {
    return buildString {
        append("cover|")
        append(sourceName)
        append('|')
        append(url)
        if (!instanceKey.isNullOrBlank()) {
            append('|')
            append(instanceKey)
        }
    }
}

fun contentCoverSharedKey(
    content: Content,
    coverUrl: String?,
    instanceKey: String? = null,
): String {
    return contentCoverSharedKey(
        sourceName = content.source.name,
        url = contentCoverIdentity(content, coverUrl),
        instanceKey = instanceKey,
    )
}

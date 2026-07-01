package org.skepsun.kototoro.core.ui.compose

import org.skepsun.kototoro.parsers.model.Content

fun sharedCoverMemoryCacheKey(
    sourceName: String?,
    ownerKey: String?,
    url: String?,
): String? {
    val normalizedUrl = url?.takeIf { it.isNotBlank() } ?: return null
    return buildString {
        append("shared-cover")
        append('#')
        append(sourceName.orEmpty())
        append('#')
        append(ownerKey.orEmpty())
        append('#')
        append(normalizedUrl)
    }
}

fun contentCoverCacheKey(
    content: Content,
    url: String?,
): String? {
    return sharedCoverMemoryCacheKey(
        sourceName = content.source.name,
        ownerKey = content.projectionOwnerKey(),
        url = url,
    )
}

fun Content.projectionOwnerKey(): String? {
    return url.takeIf { it.isNotBlank() }
        ?: publicUrl.takeIf { it.isNotBlank() }
}

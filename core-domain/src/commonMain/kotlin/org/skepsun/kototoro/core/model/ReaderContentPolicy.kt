package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.ContentType

/** Reading scope, independent of loaded extensions. Persisted enum values remain unchanged. */
object ReaderContentPolicy {
    val contentTypes: Set<ContentType> = ContentType.entries.filterTo(linkedSetOf()) {
        it != ContentType.VIDEO && it != ContentType.HENTAI_VIDEO && it != ContentType.OTHER
    }

    private val retiredSourcePrefixes = listOf("ANIYOMI_", "CLOUDSTREAM_", "JSON_TVBOX_")

    fun acceptsSource(name: String, type: ContentType): Boolean =
        name != "LOCAL_VIDEO" && retiredSourcePrefixes.none(name::startsWith) && type in contentTypes

    /** Restored metadata wins over an unresolved extension's OTHER fallback. */
    fun acceptsSnapshot(name: String, persistedType: ContentType?, resolvedType: ContentType): Boolean =
        acceptsSource(name, persistedType ?: resolvedType)
}

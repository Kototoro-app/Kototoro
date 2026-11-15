package org.skepsun.kototoro.settings.sources.unified

import org.skepsun.kototoro.core.extensions.SourceConfigFormat
import org.skepsun.kototoro.core.extensions.SourceInputIntent
import org.skepsun.kototoro.core.extensions.detectSourceInput
import org.skepsun.kototoro.core.extensions.sniffSourceConfigFormat

/**
 * Which extension kind a fetched source address belongs to.
 *
 * The add dialog used to open with nine kinds, so pasting a link meant knowing beforehand whether it was a Mihon
 * index, an Aniyomi index or a TVBox list. The answer is in the body: TVBox/CMS declares sites, Legado spells its book
 * sources, and a repository index announces packages whose names carry the ecosystem. Only the parts that genuinely
 * look alike are left to the user, which is why this returns `null` instead of a default.
 */
internal fun resolveSourceKindForBody(body: String): UnifiedSourceKind? = when (sniffSourceConfigFormat(body)) {
    SourceConfigFormat.TVBOX_CMS -> UnifiedSourceKind.TVBOX
    SourceConfigFormat.LEGADO -> UnifiedSourceKind.LEGADO
    SourceConfigFormat.REPOSITORY_INDEX -> repositoryKindForIndexBody(body)
    SourceConfigFormat.UNKNOWN -> null
}

/**
 * The ecosystem of a repository index, read from the package entries it publishes.
 *
 * An `index.min.json` says nothing about itself, but its packages do: Aniyomi extensions carry `.animeextension.` and
 * Cloudstream plugins end in `.cs3`. Everything else is a Mihon-style index, which is the format `.pb` and the plain
 * JSON indexes are written in; a JAR repository lists plugin jars instead of extension packages.
 */
internal fun repositoryKindForIndexBody(body: String): UnifiedSourceKind {
    val lower = body.lowercase()
    return when {
        ".animeextension." in lower || "aniyomi" in lower -> UnifiedSourceKind.ANIYOMI
        ".cs3" in lower -> UnifiedSourceKind.CLOUDSTREAM
        ".jar" in lower && "packageName" !in lower -> UnifiedSourceKind.JAR
        else -> UnifiedSourceKind.MIHON
    }
}

/**
 * Kinds that the address settles on its own, before any request: a protobuf index is the Mihon format by definition,
 * and a link that is really an extension file has nothing to add as a source.
 *
 * `null` means "fetch the body and ask [resolveSourceKindForBody]"; [SourceInputIntent.EXTENSION_FILE] also returns
 * `null` so the caller can point at the file importer instead of adding a source.
 */
internal fun resolveSourceKindWithoutBody(address: String): UnifiedSourceKind? {
    val clean = address.trim()
    if (clean.isEmpty()) return null
    if (detectSourceInput(clean) != SourceInputIntent.REPOSITORY_INDEX) return null
    val path = clean.substringBefore('?').substringBefore('#').lowercase()
    return if (path.endsWith(".pb")) UnifiedSourceKind.MIHON else null
}

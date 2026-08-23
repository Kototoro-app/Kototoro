package org.skepsun.kototoro.extensions.runtime.tachiyomi

import eu.kanade.tachiyomi.source.Source
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Ecosystem-neutral handle to a single loaded Tachiyomi-ABI extension source.
 *
 * Request contexts ([org.skepsun.kototoro.mihon.compat.SourceRequestContext],
 * [org.skepsun.kototoro.mihon.compat.MihonRequestContext]) and the network/browser bridge must
 * depend on this interface instead of hard-casting to `MihonMangaSource`, so that a Tsundoku
 * novel source can be plugged in through the same seams.
 *
 * Identity / isolation rules (plan §6.2):
 * - source key:   ecosystem prefix + sourceId  (see [SourceIdentity])
 * - preferences:  ecosystem + packageName + sourceId  ([preferenceNamespace])
 * - cookies:      domain (may be shared across ecosystems)
 */
interface TachiyomiXSourceAdapter {
    val ecosystem: ExternalExtensionType
    val packageName: String
    val sourceId: Long

    /** The raw upstream extension source instance (a `SourceFactory` result). */
    val upstreamSource: Source

    /** Content type of the books this source serves (MANGA / NOVEL / ...; NSFW variant). */
    val contentType: ContentType

    /** Base URL of the website, if the source is an HttpSource. Used for browser origin context. */
    val baseUrlOrNull: String?

    /** Namespace that isolates source-level preferences: `ecosystem:packageName:sourceId`. */
    val preferenceNamespace: String

    /** Kototoro-visible strict source key, e.g. `TSUNDOKU_9001`. */
    val sourceKey: String

    /** Convenience accessor for the strict identity. */
    val identity: SourceIdentity
        get() = SourceIdentity(ecosystem, sourceId)
}

/**
 * Plain data implementation of [TachiyomiXSourceAdapter] for tests and non-Mihon ecosystems.
 */
data class TachiyomiXSourceAdapterData(
    override val ecosystem: ExternalExtensionType,
    override val packageName: String,
    override val sourceId: Long,
    override val upstreamSource: Source,
    override val contentType: ContentType,
    override val baseUrlOrNull: String? = null,
) : TachiyomiXSourceAdapter {
    override val preferenceNamespace: String
        get() = "${ecosystem.name.lowercase()}:$packageName:$sourceId"
    override val sourceKey: String
        get() = identity.sourceKey
}

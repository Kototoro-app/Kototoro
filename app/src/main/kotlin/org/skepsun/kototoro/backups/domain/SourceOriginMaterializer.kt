package org.skepsun.kototoro.backups.domain

import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiXSourceAdapter
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

/**
 * Strict, no-guessing construction of minimal `source_origins` records (plan §8.1/§8.2,
 * T2B.2/T2B.3).
 *
 * "最小 origin" = at least a row with [kind]. Kind is derived only from the known stable
 * source-key prefixes (`MIHON_`, `ANIYOMI_`, `IREADER_`, `TSUNDOKU_`); any other key becomes
 * `UNKNOWN` and is kept as-is (never guessed by package name / title).
 * Package name is only taken when the installed source strictly exposes it via
 * [TachiyomiXSourceAdapter]; display name / content type only when the installed source
 * provides them.
 */
object SourceOriginMaterializer {

    /** Kind derived from a source key, or null when the prefix is unknown (never guessed). */
    fun kindForSourceKey(sourceKey: String): String? {
        return when {
            sourceKey.startsWith("MIHON_") -> "MIHON"
            sourceKey.startsWith("ANIYOMI_") -> "ANIYOMI"
            sourceKey.startsWith("IREADER_") -> "IREADER"
            sourceKey.startsWith("TSUNDOKU_") -> "TSUNDOKU"
            else -> null
        }
    }

    /**
     * Minimal origin for [sourceKey], enriched only from strictly available [installed]
     * source information when present.
     */
    fun minimalOrigin(
        sourceKey: String,
        installed: ContentSource?,
        now: Long = System.currentTimeMillis(),
    ): SourceOriginEntity {
        val contentTypeName = installed?.contentType?.name
        val displayName = installed?.let { src ->
            when (src) {
                is MihonMangaSource -> src.displayName
                is TsundokuNovelSource -> src.displayName
                else -> if (src.name == sourceKey) null else src.name
            }
        }
        val packageName = (installed as? TachiyomiXSourceAdapter)?.packageName?.takeIf { it.isNotBlank() }
        return SourceOriginEntity(
            sourceKey = sourceKey,
            kind = kindForSourceKey(sourceKey) ?: "UNKNOWN",
            displayName = displayName,
            contentType = contentTypeName,
            packageName = packageName,
            sourceId = null,
            repositoryUrl = null,
            repositoryName = null,
            locator = null,
            versionName = null,
            versionCode = null,
            signingDigest = null,
            lastSeenAt = now,
            updatedAt = now,
        )
    }
}

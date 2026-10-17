package org.skepsun.kototoro.ios

import androidx.room.immediateTransaction
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.migration.domain.RecoveryReference
import org.skepsun.kototoro.migration.domain.RecoveryPackage
import org.skepsun.kototoro.migration.domain.MissingLibrarySource
import org.skepsun.kototoro.migration.domain.SourceRecoveryRules
import org.skepsun.kototoro.migration.domain.TitleMatchingRules

data class IosMissingSource(
    val key: String, val title: String, val works: Int, val favourites: Int, val history: Int,
    val packageKey: String? = null,
)
data class IosRecoveryPackage(val key: String, val sourceKeys: List<String>)

/** Labels are presentation only; installation always matches the full ecosystem-qualified source key. */
class IosLibraryRecovery(private val library: IosLibrary) {
    /** Remember provider identity while it is available, including parser plugins absent from source-ID indexes. */
    @Throws(Exception::class)
    suspend fun remember(sources: List<IosSource>) {
        library.database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = library.database.getSourceOriginsDao()
                val known = dao.findAll().associateBy { it.sourceKey }
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                for (source in sources.filter { it.ecosystem != "LOCAL" }) {
                    val old = known[source.name]
                    val title = source.title.takeIf { it.isNotBlank() && it != source.name }
                        ?: old?.displayName ?: source.title
                    val provider = source.group.takeIf(String::isNotBlank) ?: old?.packageName
                    if (old != null && old.displayName == title && old.packageName == provider &&
                        old.contentType == source.contentType && old.kind == source.ecosystem) continue
                    val origin = old ?: SourceOriginEntity(source.name, source.ecosystem, updatedAt = now)
                    dao.upsert(origin.copy(
                        kind = source.ecosystem, displayName = title, contentType = source.contentType,
                        packageName = provider, lastSeenAt = now, updatedAt = now))
                }
            }
        }
    }

    fun recommended(missing: List<IosMissingSource>, packages: List<IosRecoveryPackage>): List<String> =
        SourceRecoveryRules.recommended(missing.map {
            MissingLibrarySource(it.key, it.works, it.favourites, it.history)
        }, packages.map { RecoveryPackage(it.key, it.sourceKeys) })

    @Throws(Exception::class)
    suspend fun missing(installedSources: List<String>): List<IosMissingSource> =
        library.database.useReaderConnection { connection ->
            connection.deferredTransaction {
                val references = library.database.getMangaDao().findLibrarySourceReferences(historyLimit = 200).map {
                    RecoveryReference(it.source, it.id.toString(), it.favourite)
                }
                val origins = library.database.getSourceOriginsDao().findAll().associateBy { it.sourceKey }
                SourceRecoveryRules.missing(references, installedSources.toSet()).map {
                    val origin = origins[it.sourceKey]
                    IosMissingSource(it.sourceKey, origin?.displayName?.takeIf(String::isNotBlank) ?: it.sourceKey,
                        it.works, it.favourites, it.history,
                        origin?.packageName?.takeIf(String::isNotBlank)?.let { pkg -> "${origin.kind}:$pkg" })
                }
            }
        }
}

/** Reuses Android's title matching; candidates still require explicit user selection. */
object IosMigrationMatching {
    fun uniqueExactMatch(original: IosSourceContent, candidates: List<IosSourceContent>): IosSourceContent? =
        rank(original, candidates).filter { similarity(original, it) == 1.0 }.singleOrNull()

    fun rank(original: IosSourceContent, candidates: List<IosSourceContent>): List<IosSourceContent> =
        candidates.filter { it.id != original.id && it.sourceName != original.sourceName &&
            it.mediaKind == original.mediaKind }.distinctBy { it.sourceName to it.id }
            .sortedByDescending { similarity(original, it) }

    private fun similarity(original: IosSourceContent, candidate: IosSourceContent): Double =
        TitleMatchingRules.bestSimilarity(listOf(original.title) + original.altTitles,
            listOf(candidate.title) + candidate.altTitles) { TitleMatchingRules.normalizeFoldedTitle(it.lowercase()) }
}

package org.skepsun.kototoro.tsundoku

import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records `source_origins` rows for successfully scanned Tsundoku extension packages
 * (plan T3A.7).
 *
 * Invariants:
 * - Only a full-package [TsundokuLoadResult.Success] upserts origins (per source);
 *   per-source rejections are skipped without touching that source's origin.
 * - The origin registry is long-term (plan §6.8): when a package or source disappears
 *   (uninstall / reinstall), nothing is deleted — [recordLoadResults] never calls any
 *   delete method. Missing sources simply stop refreshing `last_seen_at`.
 * - Every field written is strictly derivable from the scan result or the installed APK
 *   signature; nothing is guessed.
 */
@Singleton
class TsundokuOriginRecorder @Inject constructor(
    private val originsDao: SourceOriginsDao,
    private val signatureValidator: InstalledExtensionSignatureValidator,
) {

    suspend fun recordLoadResults(results: List<TsundokuLoadResult>) {
        val now = System.currentTimeMillis()
        for (success in results.filterIsInstance<TsundokuLoadResult.Success>()) {
            recordSuccess(success, now)
        }
        // 卸载/缺失绝不删除 origin：注册表长期保留（plan §6.8/T3A.7）。
    }

    suspend fun recordSuccess(
        success: TsundokuLoadResult.Success,
        now: Long = System.currentTimeMillis(),
    ) {
        val digest = signatureValidator.firstFingerprint(success.pkgName)
        for (source in success.sources) {
            originsDao.upsert(
                SourceOriginEntity(
                    sourceKey = "TSUNDOKU_${source.id}",
                    kind = "TSUNDOKU",
                    displayName = source.name,
                    contentType = if (success.isNsfw) "HENTAI_NOVEL" else "NOVEL",
                    packageName = success.pkgName,
                    sourceId = source.id.toString(),
                    repositoryUrl = null,
                    repositoryName = null,
                    locator = null,
                    versionName = success.versionName,
                    versionCode = success.versionCode,
                    signingDigest = digest,
                    lastSeenAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}

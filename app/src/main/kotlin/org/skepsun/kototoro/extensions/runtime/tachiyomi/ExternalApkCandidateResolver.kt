package org.skepsun.kototoro.extensions.runtime.tachiyomi

import android.content.pm.PackageInfo

/**
 * How candidates from different install sources (system packages vs. app-private local APKs)
 * are merged into one list per package name (plan §7.3).
 *
 * - [SYSTEM_FIRST_KEEP_FIRST] preserves the historical Mihon semantics: the system package
 *   always wins for a package name, regardless of version. This is what `MihonExtensionLoader`
 *   does today and is pinned by characterization tests.
 * - [VERSION_HIGHER_FIRST_TIE_SYSTEM] selects the highest `versionCode` first and breaks ties
 *   in favour of the system package. Per the plan this rule is applied only to Tsundoku.
 */
enum class ExternalApkCandidateSelection {
    SYSTEM_FIRST_KEEP_FIRST,
    VERSION_HIGHER_FIRST_TIE_SYSTEM,
}

/**
 * Pure candidate selection (T1.2): merges installed-system and app-private local packages into
 * one candidate per package name according to [ExternalApkCandidateSelection].
 *
 * No Android scanning happens here, so the rules are fully unit-testable.
 */
object ExternalApkCandidateResolver {

    fun resolve(
        installed: List<PackageInfo>,
        local: List<PackageInfo>,
        mode: ExternalApkCandidateSelection,
    ): List<PackageInfo> = when (mode) {
        ExternalApkCandidateSelection.SYSTEM_FIRST_KEEP_FIRST -> {
            (installed + local).distinctBy { it.packageName }
        }

        ExternalApkCandidateSelection.VERSION_HIGHER_FIRST_TIE_SYSTEM -> {
            @Suppress("DEPRECATION")
            val select = fun(candidates: List<Candidate>): PackageInfo {
                return candidates.maxWith(
                    compareBy<Candidate> { it.pkgInfo.versionCode }
                        .thenBy { it.fromSystem },
                ).pkgInfo
            }
            (installed.map { Candidate(it, fromSystem = true) } +
                local.map { Candidate(it, fromSystem = false) })
                .groupBy { it.pkgInfo.packageName }
                .map { (_, candidates) -> select(candidates) }
        }
    }

    private data class Candidate(
        val pkgInfo: PackageInfo,
        val fromSystem: Boolean,
    )
}

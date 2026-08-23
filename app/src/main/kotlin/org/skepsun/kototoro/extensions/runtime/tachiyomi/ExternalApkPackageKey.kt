package org.skepsun.kototoro.extensions.runtime.tachiyomi

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

/**
 * Stable identity of one extension **package** within one ecosystem.
 *
 * The (ecosystem, packageName) pair is the catalog/install identity key: two packages with the
 * same name in different ecosystems (e.g. a Mihon and a Tsundoku extension) must never merge
 * automatically. Display name, repository and signature are never part of the key.
 *
 * @see SourceIdentity for the per-source (not per-package) identity.
 */
data class ExternalApkPackageKey(
    val ecosystem: ExternalExtensionType,
    val packageName: String,
)

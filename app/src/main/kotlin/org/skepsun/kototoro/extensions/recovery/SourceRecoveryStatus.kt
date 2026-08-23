package org.skepsun.kototoro.extensions.recovery

/**
 * Strictly derived recovery state for a source origin (never persisted).
 *
 * Every non-[RESOLVED] value means the source is, in some sense, missing — but each
 * value encodes *why* so the caller can offer the correct recovery channel without
 * guessing. Derivation rules live in [SourceRecoveryDerivation].
 */
enum class SourceRecoveryStatus {
    /** The runtime snapshot contains this source key: resolved. */
    RESOLVED,

    /** No runtime entry and no usable locator information: only "missing" can be shown. */
    MISSING,

    /** Not installed, but a [org.skepsun.kototoro.core.db.entity.SourceOriginEntity.repositoryUrl]
     *  is recorded: recoverable from the original repository. */
    REPOSITORY_REQUIRED,

    /** Not installed and no repository, but the origin is package-backed (APK/JAR class):
     *  needs a side-load (.apk/.jar). */
    SIDELOAD_REQUIRED,

    /** Not installed and the origin is a non-extension source (locator): needs re-import. */
    REIMPORT_REQUIRED,

    /** Package is installed but its current signing digest differs from the recorded one:
     *  the user must confirm the signature change before re-association. */
    SIGNATURE_CONFIRMATION_REQUIRED,
}

/** `true` for every state that should be surfaced as "missing" in the UI. */
val SourceRecoveryStatus.isMissing: Boolean
    get() = this != SourceRecoveryStatus.RESOLVED

package org.skepsun.kototoro.extensions.runtime.tachiyomi

import eu.kanade.tachiyomi.source.Source

/**
 * Phase in which a structured load failure occurred (plan §2A: ABI, manga object and
 * double-feature failures must each produce a correct, structured error).
 */
enum class TachiyomiLoadErrorPhase {
    /** Package-level invariants: missing ApplicationInfo / version name / meta-data / source class. */
    METADATA,

    /** extensions-lib version unparseable or outside the ecosystem's accepted set. */
    LIB_VERSION,

    /** ClassLoader could not be built over the APK. */
    CLASSLOADER,

    /** Direct/factory instantiation itself failed or factory threw — fatal to the whole package. */
    INSTANTIATION,

    /** Per-source validation rejected one source; the package may still load remaining legal sources. */
    SOURCE,
}

/**
 * One source rejected after instantiation (never fatal to the package unless [fatalToPackage]).
 */
data class TachiyomiSourceRejection(
    val className: String,
    val reason: String,
)

/**
 * Structured result of loading one Tachiyomi-ABI extension package through
 * [TachiyomiApkLoaderRuntime]. Kototoro-agnostic: carries upstream [Source] instances and
 * ecosystem-level diagnostics; wrapping into [org.skepsun.kototoro.parsers.model.ContentSource]
 * adapters is the manager's job.
 */
sealed interface TachiyomiLoadResult {

    /**
     * The package loaded. [sources] contains only instances that passed per-source validation;
     * [rejections] lists the rejected ones (e.g. a manga object inside a novel package).
     */
    data class Success(
        val packageName: String,
        val libVersion: String,
        val isNsfw: Boolean,
        val sources: List<Source>,
        val rejections: List<TachiyomiSourceRejection> = emptyList(),
        /** Full metadata extracted from the APK (appName, versionCode/Name, lang, …). */
        val info: TachiyomiApkLoaderRuntime.TachiyomiApkInfo,
    ) : TachiyomiLoadResult

    data class Error(
        val packageName: String,
        val phase: TachiyomiLoadErrorPhase,
        val message: String,
        val cause: Throwable? = null,
    ) : TachiyomiLoadResult
}

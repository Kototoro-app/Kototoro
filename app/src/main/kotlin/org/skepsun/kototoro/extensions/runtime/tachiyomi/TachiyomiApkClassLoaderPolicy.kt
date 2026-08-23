package org.skepsun.kototoro.extensions.runtime.tachiyomi

/**
 * Shared child-first class-loading policy for every Tachiyomi-ABI extension ecosystem
 * (Mihon, Aniyomi, IReader, Tsundoku).
 *
 * Classes whose package starts with one of [parentPackages] are always loaded by the host
 * (parent) classloader: language/stdlib/android/platform runtimes plus the host-owned ABI.
 * The host ABI includes the manga interfaces (`eu.kanade.tachiyomi.source.*`, .model/online)
 * as well as the novel ABI (`eu.kanade.tachiyomi.source.NovelSource`,
 * `eu.kanade.tachiyomi.source.SourceTracker`, `eu.kanade.tachiyomi.source.model.RefreshContext`,
 * `Page.text` … — all inside the `source.`/`model.`/`online.` prefixes below), so both manga
 * and novel extensions resolve their interfaces against exactly the classes the host provides
 * (T2A.1). Everything else is loaded child-first from the extension APK.
 */
internal object TachiyomiApkClassLoaderPolicy {

    internal val parentPackages = setOf(
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.coroutines.",
        "android.",
        "androidx.",
        "org.json.",
        "org.jsoup.",
        "okhttp3.",
        "okio.",
        "rx.",
        "eu.kanade.tachiyomi.source.",
        "eu.kanade.tachiyomi.source.model.",
        "eu.kanade.tachiyomi.source.online.",
        "eu.kanade.tachiyomi.network.",
        "eu.kanade.tachiyomi.util.",
        "uy.kohesive.injekt.",
        "ireader.core.",
        "io.ktor.",
        "com.fleeksoft.",
    )

    fun shouldDelegateToParent(className: String): Boolean {
        // Bridge/compat classes (`-CC`, `DefaultImpls`) are D8 build artifacts that travel
        // INSIDE the extension APK (e.g. NovelSourcery bundles `NovelSource$-CC` /
        // `SourceTracker$-CC` and calls `$default$isNovelSource`). They have no host-side
        // equivalent, so they must load child-first; only the *interfaces themselves*
        // keep resolving against the host so ABI identity stays single.
        if (className.endsWith("$-CC") || className.contains("\$DefaultImpls")) {
            return false
        }
        return parentPackages.any(className::startsWith)
    }
}

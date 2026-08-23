package org.skepsun.kototoro.settings.sources.unified

import android.net.Uri
import android.os.Bundle
import java.util.Locale

/**
 * Parsed deep-link payload for the unified sources screen (Tsundoku Phase 5 / T5.2).
 *
 * Kept a pure value type so the caller (Activity / ViewModel) never has to deal with raw
 * Uri query parameters or `Bundle` extras. The parser is deliberately framework-light:
 * everything beyond `Uri`/`Bundle` input is plain Kotlin, so the whole parsing pipeline is
 * unit-testable in the JVM.
 *
 * Semantics:
 *  - [initialTab] is normalized to one of the four canonical values
 *    (`sources` / `repos` / `installed` / `recovery`); unknown or blank values resolve to
 *    `null` (treated as "no tab override").
 *  - [packageFilter] / [sourceKey] are trimmed; blank values resolve to `null`.
 *  - Parameter name matching is case-insensitive; value matching (for the tab) is
 *    case-insensitive as well.
 */
data class UnifiedSourcesDeepLink(
    val initialTab: String? = null,
    val packageFilter: String? = null,
    val sourceKey: String? = null,
)

object UnifiedSourcesDeepLinkParser {

    // Extras keys — documented on the Activity as well, exposed here so both the parser
    // and any caller (deep link builder from Settings, pending-state save) share them.
    const val EXTRA_INITIAL_TAB = "initial_tab"
    const val EXTRA_PACKAGE_FILTER = "package_filter"
    const val EXTRA_SOURCE_KEY = "source_key"

    // Canonical tab values consumed by `UnifiedSourcesViewModel.applyDeepLink`.
    const val TAB_SOURCES = "sources"
    const val TAB_REPOS = "repos"
    const val TAB_INSTALLED = "installed"

    /** Aliases accepted for each canonical tab. Lookup keys are normalized (lowercase). */
    private val TAB_ALIASES = mapOf(
        TAB_SOURCES to TAB_SOURCES,
        "source" to TAB_SOURCES,
        TAB_REPOS to TAB_REPOS,
        "repositories" to TAB_REPOS,
        "repository" to TAB_REPOS,
        "repo" to TAB_REPOS,
        TAB_INSTALLED to TAB_INSTALLED,
        "packages" to TAB_INSTALLED,
        "package" to TAB_INSTALLED,
    )

    /**
     * Parses the unified-sources deep link from a [Uri]'s query parameters:
     * `tab` / `package` / `source`. Assumes the URI was already matched as a Kototoro
     * sources deep link; a non-hierarchical or parameter-less URI yields an empty link
     * (no exceptions).
     */
    fun fromUri(uri: Uri): UnifiedSourcesDeepLink {
        return UnifiedSourcesDeepLink(
            initialTab = uri.getQueryParameter("tab").normalizeTab(),
            packageFilter = uri.getQueryParameter("package").normalizedValue(),
            sourceKey = uri.getQueryParameter("source").normalizedValue(),
        )
    }

    /**
     * Parses the deep link from Activity intent extras using [EXTRA_INITIAL_TAB],
     * [EXTRA_PACKAGE_FILTER] and [EXTRA_SOURCE_KEY]. Missing extras / missing keys simply
     * leave the corresponding field `null`. A `null` (or empty) [Bundle] yields an empty link.
     */
    fun fromExtras(extras: Bundle?): UnifiedSourcesDeepLink {
        if (extras == null || extras.isEmpty) {
            return UnifiedSourcesDeepLink()
        }
        return UnifiedSourcesDeepLink(
            initialTab = extras.getString(EXTRA_INITIAL_TAB).normalizeTab(),
            packageFilter = extras.getString(EXTRA_PACKAGE_FILTER).normalizedValue(),
            sourceKey = extras.getString(EXTRA_SOURCE_KEY).normalizedValue(),
        )
    }

    /**
     * Merges two links with a defined precedence: **Uri wins over extras** on every field;
     * the extras fill any gaps the Uri left `null`. The Activity applies this rule when both
     * an `intent.data` Uri and intent extras are present.
     */
    fun merge(uri: UnifiedSourcesDeepLink, extras: UnifiedSourcesDeepLink): UnifiedSourcesDeepLink {
        return UnifiedSourcesDeepLink(
            initialTab = uri.initialTab ?: extras.initialTab,
            packageFilter = uri.packageFilter ?: extras.packageFilter,
            sourceKey = uri.sourceKey ?: extras.sourceKey,
        )
    }

    /**
     * Normalizes a raw tab value to a canonical tab, or `null` when the value is blank or
     * unknown (= no tab override, as documented by the caller).
     */
    private fun String?.normalizeTab(): String? {
        if (isNullOrBlank()) {
            return null
        }
        return TAB_ALIASES[trim().lowercase(Locale.ROOT)]
    }

    private fun String?.normalizedValue(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

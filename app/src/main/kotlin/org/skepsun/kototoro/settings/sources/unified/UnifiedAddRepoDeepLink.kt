package org.skepsun.kototoro.settings.sources.unified

import android.net.Uri
import java.util.Locale

/**
 * Parsed payload for the `add-repo` deep link (`kototoro://add-repo?url=...&kind=...`).
 *
 * Historically only Mihon-compatible schemes were supported and the repository kind was
 * inferred from the URI scheme (`tachiyomi://`, `aniyomi://`, ...). The external repository
 * directory site (kototoro-repo-hub) needs one link format for every unified source kind,
 * so the `kind` query parameter is now authoritative when present, and the scheme only
 * acts as a fallback for legacy links.
 */
data class UnifiedAddRepoDeepLink(
    val kind: UnifiedSourceKind?,
    val url: String?,
) {
    companion object {
        val EMPTY = UnifiedAddRepoDeepLink(kind = null, url = null)
    }
}

object UnifiedAddRepoDeepLinkParser {

    const val PARAM_KIND = "kind"
    const val PARAM_URL = "url"

    /**
     * Parses `kind` and `url` query parameters from an `add-repo` deep-link [Uri].
     * A missing/blank parameter resolves to `null` (the caller decides how to fall back).
     */
    fun fromUri(uri: Uri?): UnifiedAddRepoDeepLink {
        if (uri == null) {
            return UnifiedAddRepoDeepLink.EMPTY
        }
        return UnifiedAddRepoDeepLink(
            kind = uri.getQueryParameter(PARAM_KIND).parseKind(),
            url = uri.getQueryParameter(PARAM_URL).normalizedUrl(),
        )
    }

    /**
     * Legacy scheme fallback: Mihon-style schemes map to [UnifiedSourceKind.MIHON],
     * Aniyomi/Anikku schemes map to [UnifiedSourceKind.ANIYOMI]. Anything else resolves
     * to `null` so callers can keep their existing behavior.
     */
    fun kindFromScheme(scheme: String?): UnifiedSourceKind? {
        return when (scheme?.trim()?.lowercase(Locale.ROOT)) {
            "aniyomi", "anikku" -> UnifiedSourceKind.ANIYOMI
            "tachiyomi", "kototoro" -> UnifiedSourceKind.MIHON
            else -> null
        }
    }

    private fun String?.parseKind(): UnifiedSourceKind? {
        if (isNullOrBlank()) {
            return null
        }
        val normalized = trim().lowercase(Locale.ROOT)
        return enumValues<UnifiedSourceKind>().firstOrNull { it.name.lowercase(Locale.ROOT) == normalized }
    }

    private fun String?.normalizedUrl(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

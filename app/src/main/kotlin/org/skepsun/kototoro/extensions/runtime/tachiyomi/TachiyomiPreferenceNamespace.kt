package org.skepsun.kototoro.extensions.runtime.tachiyomi

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Maps the raw Tachiyomi-ABI preference key (`"source_<id>"`) to an ecosystem-isolated key.
 *
 * Every Tachiyomi-ABI ecosystem resolves its source preferences through
 * `ConfigurableSource.getSourcePreferences()`, which reads `"source_$id"` from the injected
 * `Application` — so all ecosystems share one global `source_<id>` namespace per numeric id.
 * Mihon and Tsundoku both derive ids from their own (conventionally overlapping) numbering, so
 * two extensions of different ecosystems can end up with the same numeric id and would silently
 * read and write the **same** SharedPreferences file (plan §6.2 / T3B.5 cross-ecosystem
 * contamination).
 *
 * Tsundoku sources are remapped to `source_tsundoku_<pkg>_<id>`, where `<pkg>` is the
 * `packageName` tail segment after `:` (or the full package name when there is no colon), capped
 * at 64 chars so the resulting file name stays well within filesystem limits. Mihon / Aniyomi /
 * IReader keep the raw key unchanged: their id spaces are already distinct, and rewriting
 * existing preference files would orphan user data for no benefit, so this function intentionally
 * does not migrate legacy data for them.
 *
 * The function is pure (no Android or Injekt dependencies) so it can be unit-tested directly.
 * Callers decide [currentSource]; the Injekt bridge passes
 * `MihonRequestContext.currentSource()`.
 *
 * @param rawKey the preference name as requested by the extension, e.g. `"source_12345"`.
 * @param currentSource the source active in the current request context, or null
 *   (e.g. background threads that lost the per-source context).
 * @return the ecosystem-isolated preference name, or [rawKey] unchanged for non-TSUNDOKU /
 *   unknown sources.
 */
fun remapTachiyomiPreferenceKey(rawKey: String, currentSource: ContentSource?): String {
    val adapter = currentSource as? TachiyomiXSourceAdapter ?: return rawKey
    if (adapter.ecosystem != ExternalExtensionType.TSUNDOKU) return rawKey
    // Example: source_12345 -> source_tsundoku_com.example.novel_12345
    val pkg = adapter.packageName.substringAfterLast(':').takeLast(64)
    return rawKey.replaceFirst("source_", "source_tsundoku_${pkg}_")
}

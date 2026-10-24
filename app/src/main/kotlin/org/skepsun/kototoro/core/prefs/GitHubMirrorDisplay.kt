package org.skepsun.kototoro.core.prefs

import android.content.Context
import org.skepsun.kototoro.R

/**
 * Localized display label for a mirror, which needs Android's string resources and therefore stays here while the
 * catalog itself (and its URL rewriting rules) lives in `core-net` so the Windows host can share it.
 *
 * Only the direct-connection entry has a translatable label: every other mirror *is* a hostname, which must not be
 * translated or localised. The previous implementation looked the label up by position in a `string-array`, which meant
 * the list could not grow or shrink without hand-editing every locale's array in exactly the same order — with ~28
 * public mirrors that coupling is unmaintainable.
 */
fun GitHubMirrorEntry.displayName(context: Context): String =
    if (id == GitHubMirrorCatalog.NATIVE.id) {
        context.getString(R.string.github_mirror_native)
    } else {
        name
    }

package org.skepsun.kototoro.backups.external

/**
 * Built-in (non-extension) sources that Mihon-family forks ship inside the app, and the
 * mapping of their backups to Kototoro-native parser sources.
 *
 * Regular Mihon/Tachiyomi forks store manga from extension APKs, whose numeric source ids
 * are ecosystem-wide stable and therefore resolve against the user's installed Mihon
 * extensions. A few forks additionally bundle sources inside the app with hardcoded or
 * fork-derived ids that never match any extension:
 *
 *  - TachiyomiSY bundles E-Hentai / ExHentai with `EH_SOURCE_ID = 6901`, `EXH_SOURCE_ID = 6902`
 *    (see `exh.source.SourceIds` in jobobby04/TachiyomiSY; Komikku's legacy ids are the same).
 *  - Komikku bundles multi-language E-Hentai / ExHentai with its own "all" ids
 *    `EH_SOURCE_ID = 1713178126840476467`, `EXH_SOURCE_ID = 6225928719850211219`
 *    (see `exh.source.SourceIds` in komikku-app/komikku). Its per-language ids are regular
 *    extension ids and need no remapping.
 *  - Neko is MangaDex-only: its built-in source id is the first 8 bytes of
 *    `MD5("mangadex/<lang>/1")` (e.g. en = 2499283573021220255), which also differs from
 *    the official MangaDex extension id.
 *
 * For imports we therefore do two things:
 *  1. remap the known fork-built-in ids to the corresponding native Kotatsu parser source
 *     ([nativeSourceNameForId]); and
 *  2. when a `MIHON_<id>` source is *not* an installed Mihon extension at all, infer the
 *     parser source from the manga URL ([nativeSourceNameForMihonUrl]). This covers Neko's
 *     per-language MangaDex ids and any future fork without hardcoding every id.
 */
internal object MihonForkBuiltinSources {

    // -- TachiyomiSY / Komikku (legacy) built-in E-Hentai / ExHentai ids -----------------
    const val TACHIYOMI_SY_EHENTAI_ID = 6901L
    const val TACHIYOMI_SY_EXHENTAI_ID = 6902L

    // -- Komikku built-in "all" E-Hentai / ExHentai ids ---------------------------------
    const val KOMIKKU_EHENTAI_ID = 1_713_178_126_840_476_467L
    const val KOMIKKU_EXHENTAI_ID = 6_225_928_719_850_211_219L

    // Additional bundled TachiyomiSY/Komikku lewd ids kept for documentation only.
    const val SY_LEWD_PURURIN_ID = 2_221_515_250_486_218_861L
    const val SY_LEWD_TSUMINO_ID = 6_707_338_697_138_388_238L
    const val SY_LEWD_EIGHTMUSES_ID = 1_802_675_169_972_965_535L
    const val SY_LEWD_HBROWSE_ID = 1_401_584_337_232_758_222L
    const val SY_LEWD_MERGED_ID = 6969L

    /** Native Kotatsu parser source for E-Hentai / ExHentai (same target as Venera imports). */
    const val EXHENTAI_NATIVE_SOURCE = "EXHENTAI"

    /** Native Kotatsu parser source for MangaDex (same target as Venera imports). */
    const val MANGADEX_NATIVE_SOURCE = "MANGADEX"

    /**
     * Maps a fork-built-in source id to the Kototoro-native parser source it corresponds
     * to, or `null` when the id is a regular extension id (or has no native equivalent).
     *
     * Neko / per-language MangaDex ids are deliberately not enumerated here: they are
     * handled by [nativeSourceNameForMihonUrl] instead.
     */
    fun nativeSourceNameForId(id: Long): String? = when (id) {
        TACHIYOMI_SY_EHENTAI_ID,
        TACHIYOMI_SY_EXHENTAI_ID,
        KOMIKKU_EHENTAI_ID,
        KOMIKKU_EXHENTAI_ID -> EXHENTAI_NATIVE_SOURCE

        else -> null
    }

    /**
     * Infers the native Kotatsu parser source for a `MIHON_<id>` manga whose source id is
     * already known not to be an installed Mihon extension, based on the manga URL.
     * Conservative on purpose: only unambiguous E-Hentai / MangaDex URL shapes are matched.
     */
    fun nativeSourceNameForMihonUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        return when {
            lower.contains("e-hentai.org") ||
                lower.contains("exhentai.org") -> EXHENTAI_NATIVE_SOURCE

            startsWithGalleryPath(lower) -> EXHENTAI_NATIVE_SOURCE

            lower.contains("mangadex.org") ||
                startsWithPathSegment(lower, "title") -> MANGADEX_NATIVE_SOURCE

            else -> null
        }
    }

    /**
     * E-Hentai gallery URLs stored by Tachiyomi-family apps look like `g/<gallery-id>/<token>/`,
     * with or without a leading slash (e.g. `/g/123456/abc/`).
     */
    private fun startsWithGalleryPath(url: String): Boolean {
        val base = url.removePrefix("/")
        if (!base.startsWith("g/")) return false
        val galleryId = base.substringAfter("g/").substringBefore('/')
        return galleryId.toLongOrNull() != null
    }

    private fun startsWithPathSegment(url: String, segment: String): Boolean {
        val base = url.removePrefix("/")
        if (base == segment) return true
        return base.startsWith("$segment/")
    }
}

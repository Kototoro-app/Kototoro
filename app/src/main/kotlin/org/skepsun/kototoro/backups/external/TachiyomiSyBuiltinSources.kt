package org.skepsun.kototoro.backups.external

/**
 * Numeric source ids used by TachiyomiSY's *bundled* (non-extension) "lewd" sources.
 *
 * TachiyomiSY ships E-Hentai / ExHentai (and a few more) as built-in sources inside the
 * app rather than as Mihon-ecosystem extension APKs. Their ids are small hardcoded
 * constants (see `exh.source.SourceIds` in jobobby04/TachiyomiSY):
 *
 *   EH_SOURCE_ID    = 6901
 *   EXH_SOURCE_ID   = 6902
 *   PURURIN         = 2221515250486218861
 *   TSUMINO         = 6707338697138388238
 *   EIGHTMUSES      = 1802675169972965535
 *   HBROWSE         = 1401584337232758222
 *   MERGED_SOURCE_ID= 6900 + 69 = 6969
 *
 * These ids never match a real Mihon extension (extensions use large derived ids), so a
 * backup row imported verbatim as `MIHON_6901` / `MIHON_6902` can never resolve to any
 * source and is silently rendered as the generic "Mihon" label. Kototoro has a native
 * Kotatsu parser source for E-Hentai/ExHentai (`EXHENTAI`), so importable rows should be
 * remapped to it.
 */
internal object TachiyomiSyBuiltinSources {

    const val EHENTAI_ID = 6901L
    const val EXHENTAI_ID = 6902L

    // Additional bundled TachiyomiSY sources for documentation / future mapping.
    const val PURURIN_ID = 2_221_515_250_486_218_861L
    const val TSUMINO_ID = 6_707_338_697_138_388_238L
    const val EIGHTMUSES_ID = 1_802_675_169_972_965_535L
    const val HBROWSE_ID = 1_401_584_337_232_758_222L
    const val MERGED_ID = 6969L

    /**
     * Maps a TachiyomiSY built-in source id to the Kototoro-native parser source name it
     * corresponds to, or `null` when there is no native equivalent (the id is then kept
     * verbatim as `MIHON_<id>` rather than failing the import).
     */
    fun nativeSourceNameForId(id: Long): String? = when (id) {
        EHENTAI_ID, EXHENTAI_ID -> EXHENTAI_NATIVE_SOURCE
        else -> null
    }

    /** Native Kotatsu parser source name for E-Hentai / ExHentai (matches the Venera
     *  backup mapping target used elsewhere, `EXHENTAI`). */
    const val EXHENTAI_NATIVE_SOURCE = "EXHENTAI"
}

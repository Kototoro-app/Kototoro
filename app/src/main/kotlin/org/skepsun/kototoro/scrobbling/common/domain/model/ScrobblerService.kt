package org.skepsun.kototoro.scrobbling.common.domain.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

enum class ScrobblerService(
    val id: Int,
    @StringRes val titleResId: Int,
    @DrawableRes val iconResId: Int,
) {

    SHIKIMORI(1, R.string.shikimori, R.drawable.ic_shikimori_brand),
    ANILIST(2, R.string.anilist, R.drawable.ic_anilist_brand),
    MAL(3, R.string.mal, R.drawable.ic_mal_brand),
    KITSU(4, R.string.kitsu, R.drawable.ic_kitsu_brand),
    BANGUMI(5, R.string.bangumi, R.drawable.ic_bangumi),
    MANGAUPDATES(6, R.string.mangaupdates, R.drawable.ic_mangaupdates),
    SIMKL(7, R.string.simkl, R.drawable.ic_simkl_brand),
    MANGABAKA(8, R.string.mangabaka, R.drawable.ic_mangabaka),
}

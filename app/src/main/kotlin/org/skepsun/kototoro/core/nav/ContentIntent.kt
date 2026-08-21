package org.skepsun.kototoro.core.nav

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter.Companion.KEY_ID
import org.skepsun.kototoro.core.nav.AppRouter.Companion.KEY_MANGA
import org.skepsun.kototoro.core.util.ext.getParcelableCompat
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.parsers.model.Content

class ContentIntent private constructor(
    @JvmField val manga: Content?,
    @JvmField val id: Long,
    @JvmField val uri: Uri?,
) {

    constructor(intent: Intent?) : this(
        manga = intent?.getParcelableExtraCompat<ParcelableContent>(KEY_MANGA)?.manga,
        id = intent?.getLongExtra(KEY_ID, ID_NONE) ?: ID_NONE,
        uri = intent?.data,
    )

    constructor(savedStateHandle: SavedStateHandle) : this(
        manga = savedStateHandle.get<ParcelableContent>(KEY_MANGA)?.manga,
        id = savedStateHandle[KEY_ID] ?: ID_NONE,
        uri = savedStateHandle[AppRouter.KEY_DATA],
    )

    constructor(args: Bundle?) : this(
        manga = args?.getParcelableCompat<ParcelableContent>(KEY_MANGA)?.manga,
        id = args?.getLong(KEY_ID, ID_NONE) ?: ID_NONE,
        uri = null,
    )

    val mangaId: Long
        get() = if (id != ID_NONE) id else manga?.id ?: uri?.lastPathSegment?.toLongOrNull() ?: ID_NONE

    companion object {

        const val ID_NONE = 0L

        fun of(manga: Content) = ContentIntent(manga, manga.id, null)

        fun of(mangaId: Long) = ContentIntent(null, mangaId, null)
    }
}

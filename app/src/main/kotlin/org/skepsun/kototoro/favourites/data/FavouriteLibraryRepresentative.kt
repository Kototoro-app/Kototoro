package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** Lightweight identity projection used before the library loads card details in batches. */
data class FavouriteLibraryRepresentative(
    @Embedded val favourite: WorkFavouriteEntity,
    @ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
)

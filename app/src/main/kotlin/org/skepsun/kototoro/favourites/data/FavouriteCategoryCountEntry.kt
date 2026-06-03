package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo

data class FavouriteCategoryCountEntry(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "nsfw") val isNsfw: Boolean,
)

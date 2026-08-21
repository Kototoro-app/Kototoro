package org.skepsun.kototoro.favourites.data

import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.list.domain.ListSortOrder
import java.time.Instant

fun FavouriteCategoryEntity.toFavouriteCategory(id: Long = categoryId.toLong()) = FavouriteCategory(
    id = id,
    title = title,
    sortKey = sortKey,
    order = ListSortOrder(order, ListSortOrder.NEWEST),
    createdAt = Instant.ofEpochMilli(createdAt),
    isTrackingEnabled = track,
    isVisibleInLibrary = isVisibleInLibrary,
)

fun FavouriteContent.toContent() = manga.toContent(tags.toContentTags(), null)

fun Collection<FavouriteContent>.toContentList() = map { it.toContent() }

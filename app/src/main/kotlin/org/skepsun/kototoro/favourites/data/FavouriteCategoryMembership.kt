package org.skepsun.kototoro.favourites.data

data class FavouriteCategoryMembership(
	val mangaId: Long,
	val categoryId: Long,
)

data class WorkFavouriteCategoryMembership(
	val entityId: Long,
	val categoryId: Long,
)

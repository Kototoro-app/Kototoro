package org.skepsun.kototoro.favourites.domain

import org.skepsun.kototoro.core.model.FavouriteCategory

data class FavouriteSearchMatch(val category: FavouriteCategory, val mangaIds: List<Long>)

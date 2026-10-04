package org.skepsun.kototoro.favourites.domain.library

import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag

/** Restores the Android tag/source object while preserving the snapshot facet identity. */
fun FavouriteFacetTag.toContentTag(): ContentTag =
    ContentTag(title = title, key = key, source = ContentSource(source))

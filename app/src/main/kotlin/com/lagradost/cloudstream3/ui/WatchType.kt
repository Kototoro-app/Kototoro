package com.lagradost.cloudstream3.ui

/**
 * Where a title stands in Cloudstream's own library. Kototoro has none of Cloudstream's resources, so the label and
 * icon ids read 0, as an unset resource does.
 */
enum class WatchType(val internalId: Int) {
	WATCHING(0),
	COMPLETED(1),
	ONHOLD(2),
	DROPPED(3),
	PLANTOWATCH(4),
	NONE(5),
	;

	val stringRes: Int get() = 0
	val iconRes: Int get() = 0

	companion object {
		fun fromInternalId(id: Int?): WatchType = entries.find { it.internalId == id } ?: NONE
	}
}

/** Where a title stands on a sync service's lists. */
enum class SyncWatchType(val internalId: Int) {
	NONE(-1),
	WATCHING(0),
	COMPLETED(1),
	ONHOLD(2),
	DROPPED(3),
	PLANTOWATCH(4),
	REWATCHING(5),
	;

	val stringRes: Int get() = 0
	val iconRes: Int get() = 0

	companion object {
		fun fromInternalId(id: Int?): SyncWatchType = entries.find { it.internalId == id } ?: NONE
	}
}

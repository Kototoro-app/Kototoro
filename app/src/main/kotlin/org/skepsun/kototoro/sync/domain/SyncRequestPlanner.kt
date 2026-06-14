package org.skepsun.kototoro.sync.domain

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRequestPlanner @Inject constructor() {

	data class InvalidationDecision(
		val favourites: Boolean,
		val history: Boolean,
	) {
		val shouldRequestSync: Boolean
			get() = favourites || history
	}

	data class AuthorityExecutionPlan(
		val requestedAuthorities: List<String>,
		val gcFavourites: Boolean,
		val gcHistory: Boolean,
	)

	fun planInvalidation(
		tables: Set<String>,
		favouritesTable: String,
		workFavouritesTable: String,
		favouriteCategoriesTable: String,
		historyTable: String,
		workHistoryTable: String,
		isFavouritesSyncActiveOrPending: Boolean,
		isHistorySyncActiveOrPending: Boolean,
	): InvalidationDecision {
		val favourites = (favouritesTable in tables || workFavouritesTable in tables || favouriteCategoriesTable in tables) &&
			!isFavouritesSyncActiveOrPending
		val history = (historyTable in tables || workHistoryTable in tables) && !isHistorySyncActiveOrPending
		return InvalidationDecision(
			favourites = favourites,
			history = history,
		)
	}

	fun planAuthorityExecution(
		favouritesRequested: Boolean,
		historyRequested: Boolean,
		authorityFavourites: String,
		authorityHistory: String,
		isFavouritesAuthorityEnabled: Boolean,
		isHistoryAuthorityEnabled: Boolean,
	): AuthorityExecutionPlan {
		val requestedAuthorities = ArrayList<String>(2)
		var gcFavourites = false
		var gcHistory = false

		if (favouritesRequested) {
			if (isFavouritesAuthorityEnabled) {
				requestedAuthorities += authorityFavourites
			} else {
				gcFavourites = true
			}
		}
		if (historyRequested) {
			if (isHistoryAuthorityEnabled) {
				requestedAuthorities += authorityHistory
			} else {
				gcHistory = true
			}
		}

		return AuthorityExecutionPlan(
			requestedAuthorities = requestedAuthorities,
			gcFavourites = gcFavourites,
			gcHistory = gcHistory,
		)
	}
}

package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.UiText

/**
 * Minimal host-side sync API required by Cloudstream plugins.
 *
 * Kototoro does not expose Cloudstream account management, so authentication deliberately remains unavailable.
 */
open class SyncAPI {
	data class LibraryList(
		val name: UiText,
		val items: List<SearchResponse>,
	)

	data class LibraryMetadata(
		val allLibraryLists: List<LibraryList>,
		val supportedListSorting: Set<Any> = emptySet(),
	)
}

class AuthUser

class SyncRepo(
	val api: SyncAPI,
) {
	fun authUser(): AuthUser? = null

	suspend fun library(): Result<SyncAPI.LibraryMetadata?> = Result.success(null)
}

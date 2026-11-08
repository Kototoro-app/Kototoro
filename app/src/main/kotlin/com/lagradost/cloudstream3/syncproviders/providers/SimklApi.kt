package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName

/** Simkl as a sync provider plugins hand to `SyncRepo` (CineStream); like AniList here, never signed in. */
class SimklApi : SyncAPI() {
	override val name = "Simkl"
	override val idPrefix = "simkl"
	override val mainUrl = "https://api.simkl.com"
	override val createAccountUrl = "$mainUrl/signup"
	override val syncIdName = SyncIdName.Simkl

	companion object {
		fun getHeaders(token: AuthToken): Map<String, String> = mapOf(
			"Authorization" to "Bearer ${token.accessToken}",
			"Content-Type" to "application/json",
		)
	}
}

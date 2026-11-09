package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi

class AccountManager private constructor() {
	companion object {
		val aniListApi: AniListApi = AniListApi()
		val simklApi: SimklApi = SimklApi()

		val syncApis: Array<SyncRepo> get() = arrayOf(SyncRepo(aniListApi), SyncRepo(simklApi))
		val allApis: Array<AuthRepo> get() = arrayOf(*syncApis)
	}
}

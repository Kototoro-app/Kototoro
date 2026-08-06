package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

class AccountManager private constructor() {
	companion object {
		val aniListApi: AniListApi = AniListApi()
	}
}

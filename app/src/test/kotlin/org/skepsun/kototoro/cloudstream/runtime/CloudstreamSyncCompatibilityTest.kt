package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudstreamSyncCompatibilityTest {
	@Test
	fun `aniList sync host remains unauthenticated`() = runTest {
		val repo = SyncRepo(AccountManager.aniListApi)

		assertNull(repo.authUser())
		assertNull(repo.library().getOrThrow())
	}

	@Test
	fun `aniList data models expose plugin ABI getters`() {
		val media = AniListApi.RecommendedMedia(
			id = 1,
			title = AniListApi.MediaTitle("Romaji", "English", null, null),
			coverImage = null,
		)
		val recommendation = AniListApi.Recommendation(1L, media)
		val metadata = SyncAPI.LibraryMetadata(emptyList())

		assertSame(media, recommendation.mediaRecommendation)
		assertTrue(metadata.allLibraryLists.isEmpty())
	}
}

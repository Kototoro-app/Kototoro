package org.skepsun.kototoro.community.data

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.community.domain.CommunityRatingSummary
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunitySnapshotStats
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.snapshot.CommunitySnapshotFetcher
import java.io.File

class CommunitySnapshotRepositoryTest {

    @TempDir
    lateinit var filesDir: File

    @Test
    fun `cached lookup loads local shards and never fetches missing files`() = runBlocking {
        val context = mockk<Context> { every { filesDir } returns this@CommunitySnapshotRepositoryTest.filesDir }
        val fetcher = mockk<CommunitySnapshotFetcher>()
        val repository = CommunitySnapshotRepository(context, fetcher)
        val key = "title key"
        val group = "group"
        val missing = "unavailable"
        val keysPath = CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(key))
        val worksPath = CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(group))
        val missingPath = CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(missing))
        val keys = mapOf(1L to listOf(key))

        assertEquals(emptyMap<Long, String>(), repository.groupsOf(keys, cachedOnly = true))
        val manifest = CommunitySnapshotManifest(
            generatedAt = 1,
            relays = emptyList(),
            powRating = 0,
            powComment = 0,
            minChartWeight = 5.0,
            files = listOf(keysPath, worksPath, missingPath).associateWith { "new hash" },
            stats = CommunitySnapshotStats(5, 5, 0, 5, 1),
        )
        val rating = CommunityRatingSummary(8.0, 8.0, 5, 5.0, List(10) { 0 })
        val work = CommunityWorkSummary(group, "comic", rating = rating)
        writeCached(CommunitySnapshotShards.MANIFEST_PATH, Json.encodeToString(manifest))
        writeCached(keysPath, Json.encodeToString(mapOf(key to group)))
        writeCached(worksPath, Json.encodeToString(mapOf(group to work)))

        assertEquals(mapOf(1L to group), repository.groupsOf(keys, cachedOnly = true))
        assertEquals(mapOf(group to work), repository.works(listOf(group, missing), cachedOnly = true))
        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    private fun writeCached(path: String, text: String) {
        val file = File(filesDir, "community/snapshot/$path")
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

package org.skepsun.kototoro.community.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunityClientRulesTest {

    private val anilist = CommunityExternalService.ANILIST
    private val bangumi = CommunityExternalService.BANGUMI
    private val now = 1_000 * CommunityExternalResolution.DAY

    @Test
    fun `a site is searched once, unless the reader's binding names the work or the medium has no catalogue`() {
        assertTrue(CommunityExternalResolution.shouldSearch(anilist, CommunityMedium.COMIC, emptyList(), null, now))
        assertFalse(CommunityExternalResolution.shouldSearch(anilist, CommunityMedium.OTHER, emptyList(), null, now))
        assertFalse(CommunityExternalResolution.shouldSearch(anilist, CommunityMedium.COMIC, listOf(CommunityExternalRef(anilist, 1)), null, now))
        assertTrue(CommunityExternalResolution.shouldSearch(bangumi, CommunityMedium.COMIC, listOf(CommunityExternalRef(anilist, 1)), null, now))
        val bound = CommunityExternalResolution.record(anilist, listOf(CommunityExternalRef(anilist, 7)), null, now)
        assertFalse(CommunityExternalResolution.shouldSearch(anilist, CommunityMedium.COMIC, emptyList(), bound, now + 1000 * CommunityExternalResolution.DAY))
    }

    @Test
    fun `nothing found waits a month and a failure backs off in minutes`() {
        val none = CommunityExternalResolution.record(anilist, emptyList(), null, now)
        assertEquals(CommunityResolutionStatus.NONE, none.status)
        assertFalse(CommunityExternalResolution.shouldSearch(anilist, CommunityMedium.COMIC, emptyList(), none, now + 29 * CommunityExternalResolution.DAY))
        assertTrue(CommunityExternalResolution.shouldSearch(anilist, CommunityMedium.COMIC, emptyList(), none, now + 30 * CommunityExternalResolution.DAY))

        val ambiguous = CommunityExternalResolution.record(anilist, listOf(CommunityExternalRef(anilist, 1), CommunityExternalRef(anilist, 2)), null, now)
        assertEquals(CommunityResolutionStatus.AMBIGUOUS, ambiguous.status)
        assertNull(ambiguous.remoteId)

        val failed = CommunityExternalResolution.record(bangumi, null, null, now)
        val failedAgain = CommunityExternalResolution.record(bangumi, null, failed, now)
        assertEquals(2, failedAgain.failures)
        assertEquals(5 * CommunityExternalResolution.MINUTE, CommunityExternalResolution.failureBackoff(1))
        assertEquals(10 * CommunityExternalResolution.MINUTE, CommunityExternalResolution.failureBackoff(2))
        assertEquals(CommunityExternalResolution.DAY, CommunityExternalResolution.failureBackoff(50))
        assertFalse(CommunityExternalResolution.shouldSearch(bangumi, CommunityMedium.COMIC, emptyList(), failedAgain, now + 9 * CommunityExternalResolution.MINUTE))
        assertTrue(CommunityExternalResolution.shouldSearch(bangumi, CommunityMedium.COMIC, emptyList(), failedAgain, now + 10 * CommunityExternalResolution.MINUTE))
        assertEquals(0, CommunityExternalResolution.record(bangumi, emptyList(), failedAgain, now).failures, "an answer resets the backoff")
    }

    @Test
    fun `a work carries the reader's binding over a searched one for the same site`() {
        val searched = listOf(
            CommunityExternalResolution.record(anilist, listOf(CommunityExternalRef(anilist, 99)), null, now),
            CommunityExternalResolution.record(bangumi, listOf(CommunityExternalRef(bangumi, 5)), null, now),
        )
        assertEquals(
            listOf(CommunityExternalRef(anilist, 1), CommunityExternalRef(bangumi, 5)),
            CommunityExternalResolution.refs(listOf(CommunityExternalRef(anilist, 1)), searched),
        )
    }

    @Test
    fun `the snapshot reader finds a work's group through the first key a shard knows`() {
        val keys = listOf("https://anilist.co/manga/1", "kototoro:t:comic:x")
        val shards = mapOf(CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(keys[1])) to mapOf(keys[1] to "group"))
        assertEquals(CommunitySnapshotReader.keyShardPaths(keys).toSet(), keys.map { CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(it)) }.toSet())
        assertEquals("group", CommunitySnapshotReader.groupOf(keys) { shard -> shards[CommunitySnapshotShards.keysPath(shard)] })
        assertNull(CommunitySnapshotReader.groupOf(listOf("unknown")) { null })
        assertEquals(2, CommunitySnapshotShards.of("anything").length)
    }

    @Test
    fun `manifests of another layout are ignored and cached files are checked by hash`() {
        val manifest = CommunitySnapshotManifest(
            generatedAt = 1, relays = emptyList(), powRating = 16, powComment = 20, minChartWeight = 5.0,
            files = mapOf("v1/keys/aa.json" to "h1"), stats = CommunitySnapshotStats(0, 0, 0, 0, 0),
        )
        val text = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(CommunitySnapshotManifest.serializer(), manifest)
        assertEquals(manifest, CommunitySnapshotReader.manifest(text))
        assertNull(CommunitySnapshotReader.manifest(text.replace("\"version\":1", "\"version\":2")))
        assertNull(CommunitySnapshotReader.manifest("not json"))
        assertEquals(CommunitySnapshotReader.CacheState.CURRENT, CommunitySnapshotReader.cacheState(manifest, "v1/keys/aa.json", "h1"))
        assertEquals(CommunitySnapshotReader.CacheState.STALE, CommunitySnapshotReader.cacheState(manifest, "v1/keys/aa.json", "old"))
        assertEquals(CommunitySnapshotReader.CacheState.ABSENT, CommunitySnapshotReader.cacheState(manifest, "v1/keys/bb.json", null))
        assertTrue(CommunitySnapshotReader.works("{\"g\":{\"id\":\"g\",\"medium\":\"comic\",\"future_field\":1}}").containsKey("g"), "unknown fields from a newer indexer are ignored")
    }
}

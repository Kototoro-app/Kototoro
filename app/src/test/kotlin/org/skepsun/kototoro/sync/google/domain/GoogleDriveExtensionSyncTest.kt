package org.skepsun.kototoro.sync.google.domain

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.MAX_SYNC_PACKAGE_SIZE_BYTES
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionRepo
import org.skepsun.kototoro.sync.google.data.model.SyncJsonSource
import org.skepsun.kototoro.sync.google.data.model.SyncSourceState
import java.util.Base64

class GoogleDriveExtensionSyncTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
    }

    @Test
    fun `snapshot backwards compatibility with missing new fields`() {
        val legacyJson = """
            {
                "schema": 1,
                "namespace": "kototoro.sync.work.v2",
                "semantic_schema": 2,
                "device_id": "test-device",
                "synced_at": 1000,
                "entity_graph": {"entities":[],"bindings":[],"relations":[],"prefs":[]},
                "content": [],
                "work": {"categories":[],"history":[],"favourites":[],"stats":[]},
                "feed": {"tracks":[],"logs":[]}
            }
        """.trimIndent()

        val decoded = json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), legacyJson)

        assertTrue(decoded.repositories.isEmpty())
        assertTrue(decoded.sourceStates.isEmpty())
        assertTrue(decoded.jsonSources.isEmpty())
        assertTrue(decoded.extensions.isEmpty())
    }

    @Test
    fun `snapshot serializes and deserializes extension repositories and sources`() {
        val original = GoogleDriveSyncSnapshot(
            repositories = listOf(
                SyncExtensionRepo(
                    type = ExternalExtensionType.MIHON,
                    baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
                    name = "Keiyoushi",
                    website = "https://keiyoushi.github.io",
                    signingKeyFingerprint = "abcd1234efgh5678",
                    updatedAt = 2000L,
                ),
            ),
            sourceStates = listOf(
                SyncSourceState(
                    source = "mangadex",
                    isEnabled = true,
                    isPinned = true,
                    sortKey = 1,
                    usedAt = 5000L,
                ),
            ),
            jsonSources = listOf(
                SyncJsonSource(
                    id = "legado_1",
                    name = "Legado Book Source",
                    type = JsonSourceType.LEGADO,
                    config = "{\"url\": \"https://example.com\"}",
                    isEnabled = true,
                    isPinned = false,
                    updatedAt = 3000L,
                ),
            ),
            extensions = listOf(
                SyncExtensionPackage(
                    packageId = "eu.kanade.tachiyomi.extension.en.mangadex",
                    name = "MangaDex",
                    kind = "mihon",
                    versionName = "1.4.2",
                    versionCode = 42L,
                    sizeBytes = 1024L,
                    fileName = "mangadex.apk",
                    isPayloadIncluded = true,
                    payloadBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
                ),
            ),
        )

        val encoded = json.encodeToString(GoogleDriveSyncSnapshot.serializer(), original)
        val decoded = json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), encoded)

        assertEquals(1, decoded.repositories.size)
        assertEquals("Keiyoushi", decoded.repositories[0].name)
        assertEquals(ExternalExtensionType.MIHON, decoded.repositories[0].type)

        assertEquals(1, decoded.sourceStates.size)
        assertEquals("mangadex", decoded.sourceStates[0].source)
        assertTrue(decoded.sourceStates[0].isEnabled)
        assertTrue(decoded.sourceStates[0].isPinned)

        assertEquals(1, decoded.jsonSources.size)
        assertEquals("Legado Book Source", decoded.jsonSources[0].name)
        assertEquals(JsonSourceType.LEGADO, decoded.jsonSources[0].type)

        assertEquals(1, decoded.extensions.size)
        val ext = decoded.extensions[0]
        assertEquals("eu.kanade.tachiyomi.extension.en.mangadex", ext.packageId)
        assertTrue(ext.isPayloadIncluded)
        assertNotNull(ext.payloadBase64)
        val decodedBytes = Base64.getDecoder().decode(ext.payloadBase64)
        assertEquals(4, decodedBytes.size)
        assertEquals(1, decodedBytes[0])
    }

    @Test
    fun `merger deduplicates repositories by type and baseUrl taking latest`() {
        val repoV1 = SyncExtensionRepo(
            type = ExternalExtensionType.MIHON,
            baseUrl = "https://repo.example.com",
            name = "Example V1",
            updatedAt = 1000L,
        )
        val repoV2 = SyncExtensionRepo(
            type = ExternalExtensionType.MIHON,
            baseUrl = "https://repo.example.com",
            name = "Example V2",
            updatedAt = 2000L,
        )

        val local = GoogleDriveSyncSnapshot(repositories = listOf(repoV1))
        val remote = GoogleDriveSyncSnapshot(repositories = listOf(repoV2))

        val merged = GoogleDriveSyncMerger.mergeSnapshots(local, remote)

        assertEquals(1, merged.repositories.size)
        assertEquals("Example V2", merged.repositories[0].name)
    }

    @Test
    fun `merger combines source states favoring enabled and pinned`() {
        val localState = SyncSourceState(
            source = "bilibili",
            isEnabled = false,
            isPinned = true,
            sortKey = 2,
            usedAt = 1000L,
        )
        val remoteState = SyncSourceState(
            source = "bilibili",
            isEnabled = true,
            isPinned = false,
            sortKey = 5,
            usedAt = 2000L,
        )

        val local = GoogleDriveSyncSnapshot(sourceStates = listOf(localState))
        val remote = GoogleDriveSyncSnapshot(sourceStates = listOf(remoteState))

        val merged = GoogleDriveSyncMerger.mergeSnapshots(local, remote)

        assertEquals(1, merged.sourceStates.size)
        val state = merged.sourceStates[0]
        assertEquals("bilibili", state.source)
        assertTrue(state.isEnabled, "Should be enabled if either side is enabled")
        assertTrue(state.isPinned, "Should be pinned if either side is pinned")
        assertEquals(5, state.sortKey)
        assertEquals(2000L, state.usedAt)
    }

    @Test
    fun `merger deduplicates extension packages taking higher version code and preferring payload`() {
        val extNoPayload = SyncExtensionPackage(
            packageId = "plugin.cloudstream.sample",
            name = "Sample Plugin",
            kind = "cloudstream",
            versionCode = 10L,
            isPayloadIncluded = false,
            payloadBase64 = null,
        )
        val extWithPayload = SyncExtensionPackage(
            packageId = "plugin.cloudstream.sample",
            name = "Sample Plugin",
            kind = "cloudstream",
            versionCode = 10L,
            isPayloadIncluded = true,
            payloadBase64 = "dGVzdA==",
        )

        val local = GoogleDriveSyncSnapshot(extensions = listOf(extNoPayload))
        val remote = GoogleDriveSyncSnapshot(extensions = listOf(extWithPayload))

        val merged = GoogleDriveSyncMerger.mergeSnapshots(local, remote)

        assertEquals(1, merged.extensions.size)
        val ext = merged.extensions[0]
        assertTrue(ext.isPayloadIncluded)
        assertEquals("dGVzdA==", ext.payloadBase64)
    }

    @Test
    fun `extension package payload threshold check`() {
        val smallSize = 1024L * 100L // 100KB
        val largeSize = 6 * 1024 * 1024L // 6MB

        assertTrue(smallSize <= MAX_SYNC_PACKAGE_SIZE_BYTES)
        assertFalse(largeSize <= MAX_SYNC_PACKAGE_SIZE_BYTES)

        // Simulated large extension (recommendation only)
        val largeExt = SyncExtensionPackage(
            packageId = "large.package.id",
            name = "Large Extension",
            kind = "mihon",
            versionCode = 1L,
            sizeBytes = largeSize,
            isPayloadIncluded = false,
            payloadBase64 = null,
        )

        assertFalse(largeExt.isPayloadIncluded)
        assertNull(largeExt.payloadBase64)
    }
}

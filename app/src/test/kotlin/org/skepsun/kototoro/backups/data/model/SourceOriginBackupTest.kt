package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

class SourceOriginBackupTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `round trip preserves every field`() {
        val entity = SourceOriginEntity(
            sourceKey = "TSUNDOKU_9001",
            kind = "TSUNDOKU",
            displayName = "NovelFull (TS)",
            contentType = "NOVEL",
            packageName = "eu.kanade.tachiyomi.novelextension.en.novelfull",
            sourceId = "9001",
            repositoryUrl = "https://example.com/repo",
            repositoryName = "NovelSourcery",
            locator = null,
            versionName = "1.6.11",
            versionCode = 11,
            signingDigest = "deadbeef",
            lastSeenAt = 123L,
            updatedAt = 456L,
        )

        val decoded = json.decodeFromString<SourceOriginBackup>(
            json.encodeToString(SourceOriginBackup.serializer(), SourceOriginBackup.fromEntity(entity)),
        )

        assertEquals(entity, decoded.toEntity())
    }

    @Test
    fun `serialized names use snake case`() {
        val backup = SourceOriginBackup.fromEntity(
            SourceOriginEntity(
                sourceKey = "TSUNDOKU_1",
                kind = "TSUNDOKU",
                sourceId = "1",
                updatedAt = 7,
            ),
        )

        val encoded = json.encodeToString(SourceOriginBackup.serializer(), backup)

        listOf(
            "source_key", "kind", "display_name", "content_type", "package_name",
            "source_id", "repository_url", "repository_name", "locator",
            "version_name", "version_code", "signing_digest", "last_seen_at", "updated_at",
        ).forEach { name ->
            assertEquals(true, encoded.contains("\"$name\""), "missing serial name $name in $encoded")
        }
    }

    @Test
    fun `absent optional fields decode as null`() {
        val decoded = json.decodeFromString<SourceOriginBackup>(
            """{"source_key":"WEIRD_XYZ","kind":"UNKNOWN"}""",
        )

        assertEquals("WEIRD_XYZ", decoded.sourceKey)
        assertNull(decoded.packageName)
        assertNull(decoded.repositoryUrl)
        assertEquals(0L, decoded.updatedAt)
    }
}

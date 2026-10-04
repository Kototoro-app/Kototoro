package org.skepsun.kototoro.sync.google.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncMerger

class GoogleDriveSyncWireFormatTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `fixed legacy JSON restores state onto its anchor with progress and tombstones intact`() {
        val legacy = json.decodeFromString(
            GoogleDriveSyncSnapshot.serializer(),
            """
            {
              "schema":1,"namespace":"kototoro.work.v2","semantic_schema":1,
              "work":{
                "history":[{"anchor_manga_id":-7,"created_at":5,"updated_at":10,
                  "chapter_id":31,"page":4,"scroll":0.5,"percent":0.25,"chapters":8,
                  "parent_chapter_id":30,"deleted_at":12}],
                "favourites":[{"anchor_manga_id":-7,"category_id":2,"created_at":6,
                  "updated_at":11,"pinned":true,"deleted_at":13}],
                "stats":[{"anchor_manga_id":-7,"started_at":7,"duration":100,"pages":3}]
              }
            }
            """.trimIndent(),
        )
        val snapshot = GoogleDriveSyncMerger.combine(listOf(legacy))!!
        val history = snapshot.history.single()
        assertEquals(-7L, history.mangaId)
        assertEquals(31L, history.chapterId)
        assertEquals(30L, history.parentChapterId)
        assertEquals(0.25f, history.percent)
        assertEquals(12L, history.deletedAt)
        val favourite = snapshot.favourites.single()
        assertEquals(-7L, favourite.mangaId)
        assertEquals(2L, favourite.categoryId)
        assertEquals(true, favourite.isPinned)
        assertEquals(13L, favourite.deletedAt)
        assertEquals(-7L, snapshot.stats.single().mangaId)
        assertEquals(100L, snapshot.stats.single().duration)
    }

    @Test
    fun `current JSON keeps protocol and persisted state field names`() {
        val snapshot = GoogleDriveSyncSnapshot(history = listOf(SyncHistory(mangaId = 1, createdAt = 2, updatedAt = 3)))
        val encoded = json.encodeToString(GoogleDriveSyncSnapshot.serializer(), snapshot)
        val fields = json.parseToJsonElement(encoded).jsonObject
        assertEquals("2", fields.getValue("schema").toString())
        assertEquals("\"kototoro.content.v3\"", fields.getValue("namespace").toString())
        assertEquals("1", fields.getValue("semantic_schema").toString())
        assertFalse("schemaVersion" in fields)
        val decoded = json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), encoded)
        assertEquals(1L, decoded.history.single().mangaId)
        assertEquals(3L, decoded.history.single().updatedAt)
    }

    @Test
    fun `compacting an already compacted snapshot is idempotent`() {
        val snapshot = GoogleDriveSyncSnapshot(
            history = listOf(
                SyncHistory(mangaId = 1, createdAt = 2, updatedAt = 3),
                SyncHistory(mangaId = 1, createdAt = 2, updatedAt = 5, deletedAt = 5),
            ),
        )
        val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!
        val repeated = GoogleDriveSyncMerger.combine(listOf(compact))!!
        assertEquals(
            json.encodeToString(GoogleDriveSyncSnapshot.serializer(), compact),
            json.encodeToString(GoogleDriveSyncSnapshot.serializer(), repeated),
        )
    }
}

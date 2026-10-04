package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.domain.BackupSection

/** The persisted shape of the Kototoro backup: section names and index defaults must never drift. */
class BackupFormatTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `section lookup is case insensitive and keeps the legacy projections entry name`() {
        assertEquals(BackupSection.HISTORY, BackupSection.of("HISTORY"))
        assertEquals(BackupSection.INDEX, BackupSection.of("index"))
        assertEquals(BackupSection.CONTENTS, BackupSection.of("projections"))
        assertNull(BackupSection.of("no-such-section"))
    }

    @Test
    fun `every section is reachable through its own entry name`() {
        BackupSection.entries.forEach { assertEquals(it, BackupSection.of(it.entryName)) }
    }

    @Test
    fun `the backup index round-trips and a legacy index gets generation 1`() {
        val index = BackupIndex(appId = "org.example", appVersion = 7, createdAt = 1234L)
        val decoded = json.decodeFromString(BackupIndex.serializer(), json.encodeToString(BackupIndex.serializer(), index))
        assertEquals("org.example", decoded.appId)
        assertEquals(7, decoded.appVersion)
        assertEquals(BackupIndex.WRITER_GENERATION_V1, decoded.transportGeneration)
        assertEquals(1234L, decoded.exportedAt) // defaults to createdAt

        // an index written before the generation fields existed must still decode
        val legacy = json.decodeFromString(
            BackupIndex.serializer(),
            """{"app_id":"a","app_version":1,"created_at":5}""",
        )
        assertEquals(BackupIndex.WRITER_GENERATION_V1, legacy.transportGeneration)
        assertEquals(1, legacy.semanticSchemaVersion)
    }

    @Test
    fun `a category without an explicit order sorts newest first`() {
        val category = json.decodeFromString(
            CategoryBackup.serializer(),
            """{"category_id":1,"created_at":0,"sort_key":0,"title":"t"}""",
        )
        assertEquals(BACKUP_DEFAULT_CATEGORY_ORDER, category.order)
        assertEquals("NEWEST", category.order)
    }
}

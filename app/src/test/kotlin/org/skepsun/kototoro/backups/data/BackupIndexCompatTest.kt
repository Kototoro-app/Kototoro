package org.skepsun.kototoro.backups.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.data.model.BackupIndex

class BackupIndexCompatTest {

    private val json = Json {
        coerceInputValues = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `should decode legacy backup index when new schema fields are missing`() {
        val decoded = json.decodeFromString<List<BackupIndex>>(
            """
            [
              {
                "app_id": "org.skepsun.kototoro",
                "app_version": 123,
                "created_at": 1710000000000
              }
            ]
            """.trimIndent(),
        ).single()

        assertEquals(BackupIndex.WRITER_GENERATION_V1, decoded.transportGeneration)
        assertEquals(1, decoded.semanticSchemaVersion)
        assertEquals(1710000000000, decoded.createdAt)
    }
}

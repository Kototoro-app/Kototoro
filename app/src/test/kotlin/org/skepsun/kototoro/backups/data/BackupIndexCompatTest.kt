package org.skepsun.kototoro.backups.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.backups.domain.BackupSection

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

    @Test
    fun `Kotatsu export uses legacy schema and excludes Kototoro sections`() {
        val index = BackupIndex.forKotatsuCompatibility(exportedAt = 1710000000000)
        val sections = BackupRepository.ExportFormat.KOTATSU.sections

        assertEquals(BackupIndex.WRITER_GENERATION_V1, index.transportGeneration)
        assertEquals(1, index.semanticSchemaVersion)
        assertEquals(
            setOf(
                BackupSection.INDEX,
                BackupSection.HISTORY,
                BackupSection.CATEGORIES,
                BackupSection.FAVOURITES,
                BackupSection.BOOKMARKS,
                BackupSection.STATS,
            ),
            sections.toSet(),
        )
        assertFalse(sections.any { it.name.startsWith("WORK_") || it.name.startsWith("ENTITY_GRAPH_") })
    }
}

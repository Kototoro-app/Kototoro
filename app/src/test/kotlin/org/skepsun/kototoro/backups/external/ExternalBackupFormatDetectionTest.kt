package org.skepsun.kototoro.backups.external

import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExternalBackupFormatDetectionTest {

    @Test
    fun `selected Mihon format decodes with Mihon schema`() {
        val encoded = ProtoBuf.encodeToByteArray(
            MihonBackup.serializer(),
            MihonBackup(
                backupManga = listOf(
                    MihonBackupManga(
                        source = 123L,
                        url = "/manga/example",
                        title = "Example",
                    ),
                ),
                backupSources = listOf(MihonBackupSource(name = "Example Source", sourceId = 123L)),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), encoded)

        assertEquals(123L, decoded.backupManga.single().source)
        assertEquals("Example Source", decoded.backupSources.single().name)
    }

    @Test
    fun `selected Aniyomi format preserves anime data`() {
        val backup = AniyomiBackup(
            backupAnime = listOf(
                AniyomiBackupAnime(
                    source = 456L,
                    url = "/anime/example",
                    title = "Example",
                ),
            ),
        )
        val encoded = ProtoBuf.encodeToByteArray(AniyomiBackup.serializer(), backup)

        val decoded = ProtoBuf.decodeFromByteArray(AniyomiBackup.serializer(), encoded)

        assertEquals(456L, decoded.backupAnime.single().source)
    }

    @Test
    fun `selected Aniyomi format preserves anime source registry`() {
        val backup = AniyomiBackup(
            backupAnimeSources = listOf(MihonBackupSource(name = "Anime Source", sourceId = 456L)),
        )
        val encoded = ProtoBuf.encodeToByteArray(AniyomiBackup.serializer(), backup)

        val decoded = ProtoBuf.decodeFromByteArray(AniyomiBackup.serializer(), encoded)

        assertEquals("Anime Source", decoded.backupAnimeSources.single().name)
    }
}

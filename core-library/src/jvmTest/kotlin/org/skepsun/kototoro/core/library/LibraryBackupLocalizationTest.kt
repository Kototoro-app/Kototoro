package org.skepsun.kototoro.core.library

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.backups.domain.BackupSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryBackupLocalizationTest {
    @Test
    fun unsupportedVersionUsesTheSuppliedTranslationAndKeepsTheDefault() {
        val index = BackupIndex("test", 1, transportGeneration = 999, createdAt = 1)
        val translated = assertFailsWith<IllegalArgumentException> {
            LibraryBackup.requireSupported(index) { "Translated: $it" }
        }
        assertEquals("Translated: 备份版本尚不支持", translated.message)
        val original = assertFailsWith<IllegalArgumentException> { LibraryBackup.requireSupported(index) }
        assertEquals("备份版本尚不支持", original.message)
    }

    @Test
    fun emptyBackupUsesTheSuppliedTranslation() = runBlocking {
        val input = object : LibraryBackupInput {
            override suspend fun <T> rows(section: BackupSection, serializer: DeserializationStrategy<T>,
                consume: suspend (T) -> Unit) = Unit
        }
        val error = assertFailsWith<IllegalArgumentException> {
            LibraryBackup.count(input, emptySet()) { "Translated: $it" }
        }
        assertEquals("Translated: 备份没有可恢复的图书馆数据或来源节", error.message)
    }
}

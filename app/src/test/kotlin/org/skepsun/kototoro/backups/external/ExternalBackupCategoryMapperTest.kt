package org.skepsun.kototoro.backups.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExternalBackupCategoryMapperTest {

    @Test
    fun `imported category keys prefer order and keep id fallback`() {
        val mappings = linkedMapOf<Long, Long>()

        ExternalBackupCategoryMapper.putImportedCategoryKeys(
            target = mappings,
            category = ExternalBackupFavoriteCategoryRecord(
                name = "稍后阅读",
                order = 1L,
                id = 42L,
            ),
            localCategoryId = 100L,
        )

        assertEquals(100L, mappings[1L])
        assertEquals(100L, mappings[42L])
    }

    @Test
    fun `imported category keys avoid duplicate fallback when id equals order`() {
        val mappings = linkedMapOf<Long, Long>()

        ExternalBackupCategoryMapper.putImportedCategoryKeys(
            target = mappings,
            category = ExternalBackupFavoriteCategoryRecord(
                name = "收藏",
                order = 3L,
                id = 3L,
            ),
            localCategoryId = 200L,
        )

        assertEquals(mapOf(3L to 200L), mappings)
    }
}

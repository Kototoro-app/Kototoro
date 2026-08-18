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

    @Test
    fun `imported id fallback never clobbers another category order mapping`() {
        // Realistic Mihon DB: category ids are fixed row ids while the user has
        // drag-reordered the shelf, so id(A) == order(B) for unrelated A/B is common.
        // The id fallback must not re-route order(B)'s members into A.
        val mappings = linkedMapOf<Long, Long>()

        ExternalBackupCategoryMapper.putImportedCategoryKeys(
            target = mappings,
            category = ExternalBackupFavoriteCategoryRecord(name = "F", order = 2L, id = 6L),
            localCategoryId = 11L,
        )
        ExternalBackupCategoryMapper.putImportedCategoryKeys(
            target = mappings,
            category = ExternalBackupFavoriteCategoryRecord(name = "B", order = 6L, id = 2L),
            localCategoryId = 22L,
        )

        assertEquals(11L, mappings[2L], "order 2 must still map to F")
        assertEquals(22L, mappings[6L], "order 6 must map to B")
        assertEquals(mapOf(2L to 11L, 6L to 22L), mappings, "B's raw id (2) must not clobber F's order key")
    }

    @Test
    fun `restored group membership survives a fully reordered shelf`() {
        // ids = A1..H8, orders reverse (A at the bottom, H on top): every id of a
        // later-processed category equals the order of an earlier one.
        val categories = listOf(
            Triple("A", 7L, 1L), Triple("B", 6L, 2L), Triple("C", 5L, 3L),
            Triple("D", 4L, 4L), Triple("E", 3L, 5L), Triple("F", 2L, 6L),
            Triple("G", 1L, 7L), Triple("H", 0L, 8L),
        )
        val mappings = linkedMapOf<Long, Long>()
        categories.forEachIndexed { index, (name, order, id) ->
            ExternalBackupCategoryMapper.putImportedCategoryKeys(
                target = mappings,
                category = ExternalBackupFavoriteCategoryRecord(name = name, order = order, id = id),
                localCategoryId = (index + 1).toLong(),
            )
        }

        // A manga reports the ORDER values of the groups it belongs to (Mihon format);
        // each must still resolve to its own group and leave no group empty.
        categories.forEach { (name, order, _) ->
            val member = mappings[order]
            val expected = (categories.indexOfFirst { it.first == name } + 1).toLong()
            assertEquals(expected, member, "order $order of group $name must resolve to $expected")
        }
    }
}

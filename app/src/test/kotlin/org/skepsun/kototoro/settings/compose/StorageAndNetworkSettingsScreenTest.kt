package org.skepsun.kototoro.settings.compose

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory

class StorageAndNetworkSettingsScreenTest {

    @Test
    fun `missing storage usage produces only the loading row`() {
        visibleStorageUsageItems(null).size shouldBe 0
    }

    @Test
    fun `visible storage usage excludes empty categories but keeps available space`() {
        val usage = StorageUsage(
            items = listOf(
                storageItem(StorageUsageCategory.LOCAL_MANGA, bytes = 128L),
                storageItem(StorageUsageCategory.PAGES_CACHE, bytes = 0L),
                storageItem(StorageUsageCategory.AVAILABLE, bytes = 0L),
            ),
        )

        visibleStorageUsageItems(usage).map(StorageUsage.Item::category) shouldContainExactly listOf(
            StorageUsageCategory.LOCAL_MANGA,
            StorageUsageCategory.AVAILABLE,
        )
    }

    private fun storageItem(
        category: StorageUsageCategory,
        bytes: Long,
    ) = StorageUsage.Item(
        category = category,
        bytes = bytes,
        percent = 0f,
    )
}

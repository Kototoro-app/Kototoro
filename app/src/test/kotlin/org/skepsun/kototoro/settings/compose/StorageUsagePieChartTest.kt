package org.skepsun.kototoro.settings.compose

import android.content.Context
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory

class StorageUsagePieChartTest {

    private val context = mockk<Context>(relaxed = true) {
        every { getString(any()) } answers { "Label-${firstArg<Int>()}" }
    }

    @Test
    fun `null storage usage produces empty chart data`() {
        val data = computeStoragePieChartData(null, context)
        data.slices.shouldBeEmpty()
        data.totalAppBytes shouldBe 0L
        data.availableBytes shouldBe 0L
    }

    @Test
    fun `storage usage computes correct fractions and excludes available from slices`() {
        val usage = StorageUsage(
            items = listOf(
                StorageUsage.Item(category = StorageUsageCategory.THUMBS_CACHE, bytes = 300L, percent = 0.3f),
                StorageUsage.Item(category = StorageUsageCategory.PAGES_CACHE, bytes = 100L, percent = 0.1f),
                StorageUsage.Item(category = StorageUsageCategory.VIDEO_CACHE, bytes = 0L, percent = 0f),
                StorageUsage.Item(category = StorageUsageCategory.AVAILABLE, bytes = 2000L, percent = 0.6f),
            ),
        )

        val data = computeStoragePieChartData(usage, context)

        // totalAppBytes should be only the sum of non-available items with bytes > 0
        data.totalAppBytes shouldBe 400L
        data.availableBytes shouldBe 2000L

        // slices should only contain non-zero non-available categories sorted descending by bytes
        data.slices shouldHaveSize 2
        data.slices[0].category shouldBe StorageUsageCategory.THUMBS_CACHE
        data.slices[0].bytes shouldBe 300L
        data.slices[0].fraction shouldBe (0.75f plusOrMinus 0.001f)

        data.slices[1].category shouldBe StorageUsageCategory.PAGES_CACHE
        data.slices[1].bytes shouldBe 100L
        data.slices[1].fraction shouldBe (0.25f plusOrMinus 0.001f)
    }

    @Test
    fun `empty categories produce empty slices but preserve available bytes`() {
        val usage = StorageUsage(
            items = listOf(
                StorageUsage.Item(category = StorageUsageCategory.THUMBS_CACHE, bytes = 0L, percent = 0f),
                StorageUsage.Item(category = StorageUsageCategory.PAGES_CACHE, bytes = 0L, percent = 0f),
                StorageUsage.Item(category = StorageUsageCategory.AVAILABLE, bytes = 5000L, percent = 1f),
            ),
        )

        val data = computeStoragePieChartData(usage, context)
        data.slices.shouldBeEmpty()
        data.totalAppBytes shouldBe 0L
        data.availableBytes shouldBe 5000L
    }

    @Test
    fun `categoryColor returns distinct non-empty colors`() {
        val thumbsColor = categoryColor(StorageUsageCategory.THUMBS_CACHE)
        val pagesColor = categoryColor(StorageUsageCategory.PAGES_CACHE)
        (thumbsColor != pagesColor) shouldBe true
    }
}

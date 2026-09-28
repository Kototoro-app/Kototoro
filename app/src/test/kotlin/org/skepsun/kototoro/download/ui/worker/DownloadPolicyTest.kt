package org.skepsun.kototoro.download.ui.worker

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadPolicyTest {

    @Test
    fun sourceDelayFollowsTheSettingAndZeroDisablesIt() {
        DownloadPolicy.sourceDelayMs(1600) shouldBe 1_600L
        DownloadPolicy.sourceDelayMs(0) shouldBe 0L
        DownloadPolicy.sourceDelayMs(-5) shouldBe 0L
    }

    @Test
    fun retryDelayIsFixedLikeKotatsuRedo() {
        DownloadPolicy.retryDelayMs(2_000, -1L) shouldBe 2_000L
        DownloadPolicy.retryDelayMs(500, -1L) shouldBe 500L
    }

    @Test
    fun serverRetryDelayTakesPrecedence() {
        DownloadPolicy.retryDelayMs(2_000, 15_000L) shouldBe 15_000L
    }

    @Test
    fun activeSeriesLimitIsDisabledAtTheUnlimitedValue() {
        DownloadPolicy.activeSeriesLimit(4, unlimited = 11) shouldBe 4
        DownloadPolicy.activeSeriesLimit(0, unlimited = 11) shouldBe 1
        DownloadPolicy.activeSeriesLimit(11, unlimited = 11) shouldBe null
    }

    @Test
    fun duplicateDownloadRequestsHaveTheSameIdentity() {
        val first = DownloadTask.createExecutionTask(
            executionMangaId = 42L,
            isPaused = false,
            isSilent = false,
            executionChapterIds = longArrayOf(1L, 2L),
            destination = null,
            format = null,
            allowMeteredNetwork = false,
        )
        val duplicate = DownloadTask.createExecutionTask(
            executionMangaId = 42L,
            isPaused = true,
            isSilent = true,
            executionChapterIds = longArrayOf(1L, 2L),
            destination = null,
            format = null,
            allowMeteredNetwork = false,
        )
        val differentChapters = DownloadTask.createExecutionTask(
            executionMangaId = 42L,
            isPaused = false,
            isSilent = false,
            executionChapterIds = longArrayOf(1L, 3L),
            destination = null,
            format = null,
            allowMeteredNetwork = false,
        )

        first.hasSameRequestAs(duplicate) shouldBe true
        first.hasSameRequestAs(differentChapters) shouldBe false
    }
}

package org.skepsun.kototoro.tracker.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.tracker.data.TrackEntity

class FavouriteUpdatesSummaryTest : StringSpec({

    "summarizes empty track list as no updates" {
        summarizeFavouriteTracks(emptyList()) shouldBe FavouriteUpdatesSummary(
            worksWithUpdates = 0,
            newChapters = 0,
        )
    }

    "counts only works with positive new chapters and totals all pending chapters" {
        val tracks = listOf(
            track(newChapters = 3),
            track(newChapters = 0),
            track(newChapters = 2),
            track(newChapters = 1),
        )
        summarizeFavouriteTracks(tracks) shouldBe FavouriteUpdatesSummary(
            worksWithUpdates = 3,
            newChapters = 6,
        )
    }

    "ignores negative chapter counters from corrupt rows" {
        val tracks = listOf(
            track(newChapters = -5),
            track(newChapters = 0),
        )
        summarizeFavouriteTracks(tracks) shouldBe FavouriteUpdatesSummary(
            worksWithUpdates = 0,
            newChapters = 0,
        )
    }

    "counts a single updated work with multiple new chapters" {
        val tracks = listOf(track(newChapters = 10))
        summarizeFavouriteTracks(tracks) shouldBe FavouriteUpdatesSummary(
            worksWithUpdates = 1,
            newChapters = 10,
        )
    }
})

private fun track(newChapters: Int) = TrackEntity(
    ownerId = 1L,
    mangaId = 1L,
    entityId = 1L,
    lastChapterId = 0L,
    newChapters = newChapters,
    lastCheckTime = 0L,
    lastChapterDate = 0L,
    lastResult = TrackEntity.RESULT_NO_UPDATE,
    lastError = null,
)

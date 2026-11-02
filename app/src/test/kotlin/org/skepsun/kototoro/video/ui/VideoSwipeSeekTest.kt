package org.skepsun.kototoro.video.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VideoSwipeSeekTest : StringSpec({

    val episodeMs = 24 * 60_000L

    "full length keeps one screen width equal to the whole video" {
        VideoSwipeSeek.offsetMs(deltaX = 540f, widthPx = 1_080, durationMs = episodeMs, rangeSec = 0) shouldBe
            episodeMs / 2
    }

    "a shorter range scales the swipe down" {
        VideoSwipeSeek.offsetMs(deltaX = 108f, widthPx = 1_080, durationMs = episodeMs, rangeSec = 90) shouldBe 9_000L
        VideoSwipeSeek.offsetMs(deltaX = -540f, widthPx = 1_080, durationMs = episodeMs, rangeSec = 60) shouldBe -30_000L
    }

    "a range longer than the video is capped at the duration" {
        VideoSwipeSeek.offsetMs(deltaX = 1_080f, widthPx = 1_080, durationMs = 120_000L, rangeSec = 600) shouldBe
            120_000L
    }

    "invalid sizes do not seek" {
        VideoSwipeSeek.offsetMs(deltaX = 100f, widthPx = 0, durationMs = episodeMs, rangeSec = 60) shouldBe 0L
        VideoSwipeSeek.offsetMs(deltaX = 100f, widthPx = 1_080, durationMs = 0L, rangeSec = 60) shouldBe 0L
    }
})

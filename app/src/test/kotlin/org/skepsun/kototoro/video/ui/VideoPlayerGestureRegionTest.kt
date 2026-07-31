package org.skepsun.kototoro.video.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VideoPlayerGestureRegionTest : StringSpec({

    "rejects gestures starting inside the top or bottom exclusions" {
        isPlayerAdjustmentGestureStartAllowed(
            startY = 35f,
            viewHeight = 1_000,
            topExclusion = 36,
            bottomExclusion = 60,
        ) shouldBe false
        isPlayerAdjustmentGestureStartAllowed(
            startY = 941f,
            viewHeight = 1_000,
            topExclusion = 36,
            bottomExclusion = 60,
        ) shouldBe false
    }

    "allows gestures starting inside the safe adjustment region" {
        isPlayerAdjustmentGestureStartAllowed(
            startY = 500f,
            viewHeight = 1_000,
            topExclusion = 36,
            bottomExclusion = 60,
        ) shouldBe true
    }

    "rejects gestures when exclusions consume the whole view" {
        isPlayerAdjustmentGestureStartAllowed(
            startY = 100f,
            viewHeight = 120,
            topExclusion = 80,
            bottomExclusion = 80,
        ) shouldBe false
    }
})

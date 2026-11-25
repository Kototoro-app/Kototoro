package org.skepsun.kototoro.details.ui.compose

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DetailsTypeModulesTest : StringSpec({

    "the reader's rating prefers the community stars over a tracker score" {
        detailsMyRating(communityStars = 7, trackerRating = 0.9f) shouldBe 7
        detailsMyRating(communityStars = null, trackerRating = 0.8f) shouldBe 8
        detailsMyRating(communityStars = null, trackerRating = 0f) shouldBe null
    }
})
